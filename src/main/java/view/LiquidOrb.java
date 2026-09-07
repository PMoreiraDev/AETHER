package view;

import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * Orbe liquido animado do AETHER ("Liquid Orb"), transcrito 1:1 do export
 * nativo SwiftUI/Metal fornecido para a app original (estilo Siri).
 *
 * <p>Esta classe reproduz em Java puro o subconjunto do shader Metal que os
 * dois estados do AETHER realmente exercitam:</p>
 * <ul>
 *   <li>{@code glsBlueDropFluid} (style 20 — usado TANTO pelo seed IDLE como
 *       pelo seed THINKING);</li>
 *   <li>o caminho "glass" de {@code orbGlassLiquidAnim} (refração, dispersão
 *       cromática por canal, rim/sheen/specular, edge glow);</li>
 *   <li>as funções de ruído/rampa partilhadas ({@code lqFbm}, {@code lqRamp},
 *       {@code lqRidgeS}, etc.);</li>
 *   <li>a transição IDLE&lt;-&gt;THINKING entre seeds com interpolação de
 *       uniforms em espaço linear-sRGB (0.15s a "acordar", 0.65s a
 *       acalmar), tal como no original.</li>
 * </ul>
 *
 * <p>Os restantes estilos fluidos (Siri/Aurora/Plasma/Chrome/Opal/Frost/
 * VoiceWave/Metal/ParticleRibbon) existem no shader original mas não são
 * alcançáveis pelos seeds fornecidos e não são portados — manter o porto
 * pequeno é o que torna viável renderizar por software.</p>
 *
 * <p><b>Renderização:</b> cada frame é calculado por software (int[] ARGB
 * premultiplicado) num fio daemon dedicado, a ~24 fps, e publicado no fio
 * JavaFX via {@code PixelWriter.setPixels} — sem bloquear a UI e sem criar
 * garbage por pixel. Se o fio da UI estiver ocupado, frames são saltados
 * (nunca empilhados).</p>
 *
 * <p>Usado no chat do AETHER como indicador "a AI está a pensar": aparece na
 * bubble de resposta mal o utilizador envia a mensagem e é removido quando
 * chega o primeiro fragmento da resposta.</p>
 */
public class LiquidOrb extends ImageView {

    // ------------------------------------------------------------------
    // Seeds / uniforms (ordem do struct Metal "Uniforms", 136 floats)
    // ------------------------------------------------------------------
    // 0-1 size.x/y, 2 time, 3 speed, 4 radius, 5 zoom, 6 warp, 7 ridgeAmt,
    // 8 sharp, 9 shade, 10 sheen, 11 gloss, 12 shellMidAlpha, 13 shellEdgeAlpha,
    // 14 exposure, 15 style, 16 edgeSoftness, 17 edgeGlow, 18 paletteCount,
    // 19 glassEnabled, 20 glassOpacity, 21 contourDeform, 22 bandDensity,
    // 23 chromaticShift, 24-31 metal*, 32-39 particle/ribbon*, 40-55 colorA-D,
    // 56 highlight, 60 shellInner, 64 shellMid, 68 shellEdge, 72 sheenColor,
    // 76 specColor, 80 canvasColor, 84 glowColor, 88-135 palette stops.
    private static final int IDX_TIME = 2;
    private static final int IDX_SPEED = 3;
    private static final int IDX_RADIUS = 4;
    private static final int IDX_ZOOM = 5;
    private static final int IDX_WARP = 6;
    private static final int IDX_RIDGE_AMT = 7;
    private static final int IDX_SHARP = 8;
    private static final int IDX_SHADE = 9;
    private static final int IDX_SHEEN = 10;
    private static final int IDX_GLOSS = 11;
    private static final int IDX_SHELL_MID_ALPHA = 12;
    private static final int IDX_SHELL_EDGE_ALPHA = 13;
    private static final int IDX_EXPOSURE = 14;
    private static final int IDX_STYLE = 15;
    private static final int IDX_EDGE_SOFTNESS = 16;
    private static final int IDX_EDGE_GLOW = 17;
    private static final int IDX_PALETTE_COUNT = 18;
    private static final int IDX_GLASS_ENABLED = 19;
    private static final int IDX_GLASS_OPACITY = 20;
    private static final int IDX_CONTOUR_DEFORM = 21;
    private static final int IDX_COLOR_A = 40;
    private static final int IDX_COLOR_B = 44;
    private static final int IDX_COLOR_C = 48;
    private static final int IDX_COLOR_D = 52;
    private static final int IDX_HIGHLIGHT = 56;
    private static final int IDX_SHELL_INNER = 60;
    private static final int IDX_SHELL_MID = 64;
    private static final int IDX_SHELL_EDGE = 68;
    private static final int IDX_SHEEN_COLOR = 72;
    private static final int IDX_SPEC_COLOR = 76;
    private static final int IDX_CANVAS_COLOR = 80;
    private static final int IDX_GLOW_COLOR = 84;

    // Constantes do shader original.
    private static final float GL_KG = 4.1209f;
    private static final float GL_KR = 0.32f;
    private static final float GL_GH = 1.7320508f;
    private static final float GL_CLEAR_EA = 0.995f;
    private static final float GL_CLEAR_EB = 1.04f;

    /** Resolução interna de render (frame computado por software). */
    private static final int RES = 96;

    /** FPS alvo do orbe — o suficiente para fluidez sem aquecer a CPU. */
    private static final int TARGET_FPS = 24;

    /** Duração da transição IDLE -&gt; THINKING (ativação), em segundos. */
    private static final double ORB_ACTIVATION_SECONDS = 0.15;

    /** Duração da transição THINKING -&gt; IDLE (acalmar), em segundos. */
    private static final double ORB_SETTLE_SECONDS = 0.65;

    /** Formato de pixel usado na publicação (ARGB premultiplicado). */
    private static final javafx.scene.image.WritablePixelFormat<java.nio.IntBuffer> FORMAT_ARGB_PRE =
            PixelFormat.getIntArgbPreInstance();

    /** Estado visual do orbe, tal como no componente original. */
    public enum OrbState {
        IDLE, THINKING
    }

    private final WritableImage image;
    private final int[] renderBuf = new int[RES * RES];
    private final int[] publishBuf = new int[RES * RES];

    private final Object transitionLock = new Object();
    /** Cópia privada do fio de render — evita ler uniforms a serem mutados. */
    private final float[] frameUniforms = new float[136];
    private float[] fromUniforms = LiquidOrbSeeds.IDLE.clone();
    private float[] targetUniforms = LiquidOrbSeeds.IDLE.clone();
    private float[] displayedUniforms = LiquidOrbSeeds.IDLE.clone();
    private OrbState currentState = OrbState.IDLE;
    private OrbState transitionTargetState = OrbState.IDLE;
    private long transitionStartedAt = System.nanoTime();
    private double activeTransitionDuration = 0;
    private double motionPhase = 0;
    private long lastFrameAt = System.nanoTime();

    private volatile boolean running = false;
    private volatile boolean publishPending = false;
    private volatile Thread worker;

    static {
        // Os seeds fornecidos usam SEMPRE style 20 (BlueDrop) com glass
        // ativado e rampa de 4 cores (paletteCount 0). O porto cobre
        // exatamente esse caminho — falhar cedo se alguém trocar os seeds.
        for (float[] seed : new float[][]{LiquidOrbSeeds.IDLE, LiquidOrbSeeds.THINKING}) {
            if (seed.length != 136
                    || (int) (seed[IDX_STYLE] + 0.5f) != 20
                    || seed[IDX_PALETTE_COUNT] > 0.5f
                    || seed[IDX_GLASS_ENABLED] <= 0.5f) {
                throw new IllegalStateException(
                        "LiquidOrb: seeds inválidos (esperado style=20, glass=1, paletteCount=0, 136 floats)");
            }
        }
    }

    /**
     * Cria o orbe com o tamanho de apresentação indicado (o frame interno é
     * sempre renderizado a {@value #RES}px e ampliado suavemente).
     *
     * @param displaySize lado, em px lógicos, do orbe apresentado
     */
    public LiquidOrb(double displaySize) {
        image = new WritableImage(RES, RES);
        setImage(image);
        setFitWidth(displaySize);
        setFitHeight(displaySize);
        setPreserveRatio(true);
        setSmooth(true);
        setMouseTransparent(true);
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /** Inicia a animação (idempotente). Pode ser chamado no fio JavaFX. */
    public void start() {
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
            worker = new Thread(this::runLoop, "aether-orb-render");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * Pára a animação e liberta o fio de render (idempotente; pode ser
     * chamado de qualquer fio, incluindo o JavaFX). A última frame
     * apresentada permanece — quem remove o nó da cena é o chamador.
     */
    public void stop() {
        running = false;
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
    }

    /** Se a animação está a correr. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Muda o estado do orbe com transição suave entre seeds (ativação
     * 0.15s / acalmar 0.65s, cores interpoladas em espaço linear-sRGB),
     * replicando {@code LiquidOrbRenderer.setState} do original.
     */
    public void setState(OrbState state) {
        long now = System.nanoTime();
        synchronized (transitionLock) {
            if (state == currentState) {
                return;
            }
            float[] next = (state == OrbState.THINKING
                    ? LiquidOrbSeeds.THINKING : LiquidOrbSeeds.IDLE);
            float[] currentDisplayed = sampleTransition(now);
            System.arraycopy(currentDisplayed, 0, fromUniforms, 0, currentDisplayed.length);
            System.arraycopy(next, 0, targetUniforms, 0, next.length);
            transitionTargetState = state;
            transitionStartedAt = now;
            activeTransitionDuration = state == OrbState.THINKING
                    ? ORB_ACTIVATION_SECONDS : ORB_SETTLE_SECONDS;
            currentState = state;
        }
    }

    // ------------------------------------------------------------------
    // Fio de render
    // ------------------------------------------------------------------

    private void runLoop() {
        long framePeriodNs = 1_000_000_000L / TARGET_FPS;
        while (running) {
            long start = System.nanoTime();
            float[] u = snapshotFrame();
            if (u != null) {
                renderFrame(RES, RES, u, renderBuf);
                if (running && !publishPending) {
                    System.arraycopy(renderBuf, 0, publishBuf, 0, publishBuf.length);
                    publishPending = true;
                    try {
                        Platform.runLater(() -> {
                            try {
                                image.getPixelWriter().setPixels(
                                        0, 0, RES, RES, FORMAT_ARGB_PRE, publishBuf, 0, RES);
                            } catch (RuntimeException ignored) {
                                // Best-effort: a cena pode já ter sido fechada.
                            } finally {
                                publishPending = false;
                            }
                        });
                    } catch (IllegalStateException toolkitNotInitialized) {
                        // Sem toolkit JavaFX (ex.: contexto de teste) — nada a publicar.
                        publishPending = false;
                        running = false;
                        return;
                    }
                }
            }
            long elapsed = System.nanoTime() - start;
            long sleepNs = framePeriodNs - elapsed;
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /**
     * Replica o bloco de tempo do {@code draw()} original: avança a fase de
     * movimento pelo delta real de frame (limitado a 0.1s) escalado pela
     * speed ATUAL (interpolada), e deriva o time do shader da fase — assim a
     * transição de speed não salta a animação.
     */
    private float[] snapshotFrame() {
        long now = System.nanoTime();
        synchronized (transitionLock) {
            float[] u = sampleTransition(now);
            double frameDelta = Math.min(0.1, Math.max(0, (now - lastFrameAt) / 1e9));
            lastFrameAt = now;
            float speed = Math.max(u[IDX_SPEED], 0f);
            motionPhase += frameDelta * speed;
            u[IDX_TIME] = (float) (motionPhase / Math.max(speed, 0.001));
            u[0] = RES;
            u[1] = RES;
            System.arraycopy(u, 0, frameUniforms, 0, frameUniforms.length);
        }
        return frameUniforms;
    }

    /**
     * Interpola os uniforms atualmente em transição (muta e devolve
     * {@code displayedUniforms}, como no original). Componentes de cor são
     * misturados em espaço linear-sRGB; os restantes linearmente. Os índices
     * 0-2 (size/time) ficam de fora — são calculados por frame.
     */
    private float[] sampleTransition(long now) {
        double raw = activeTransitionDuration == 0 ? 1
                : Math.min(1, Math.max(0, (now - transitionStartedAt) / 1e9
                        / activeTransitionDuration));
        double eased = transitionTargetState == OrbState.THINKING
                ? 1 - Math.pow(1 - raw, 3)
                : raw * raw * (3 - 2 * raw);
        float p = (float) eased;
        for (int i = 3; i < displayedUniforms.length; i++) {
            boolean isColorComponent = i >= IDX_COLOR_A && (i - IDX_COLOR_A) % 4 < 3;
            float f = fromUniforms[i];
            float t = targetUniforms[i];
            displayedUniforms[i] = isColorComponent
                    ? mixSrgb(f, t, p)
                    : f + (t - f) * p;
        }
        return displayedUniforms;
    }

    // ------------------------------------------------------------------
    // Render de um frame (porto do shader) — visível para testes
    // ------------------------------------------------------------------

    /**
     * Renderiza um frame completo (equivalente a {@code fs_main}): para cada
     * pixel, o orbe de vidro BlueDrop com refração/dispersão + fade de borda
     * para transparência total fora do orbe.
     *
     * @param w  largura em pixels
     * @param h  altura em pixels
     * @param u  uniforms (136 floats; 0-2 preenchidos com size/time)
     * @param out buffer ARGB premultiplicado (w*h ints)
     */
    static void renderFrame(int w, int h, float[] u, int[] out) {
        float t = u[IDX_TIME] * u[IDX_SPEED];
        float minSize = Math.max(Math.min(w, h), 1f);
        float rad = Math.max(u[IDX_RADIUS], 0.05f);
        for (int y = 0; y < h; y++) {
            float fcy = y + 0.5f;
            float uvy = (2f * fcy - h) / minSize;
            for (int x = 0; x < w; x++) {
                float fcx = x + 0.5f;
                float uvx = (2f * fcx - w) / minSize;
                float[] c = orbGlassLiquidAnim(uvx, uvy, t, u);
                // fs_main: fade de borda (fit) — fora do orbe, tudo transparente.
                float qx = (2f * fcx - w) / w;
                float qy = (2f * fcy - h) / h;
                float contourRad = rad * glsContourScale(uvx, uvy, t, u[IDX_CONTOUR_DEFORM], u);
                float feather = 2f / minSize;
                float fitStart = Math.min(lerp(contourRad, 1f, 0.5f), 1f - feather);
                float fit = 1f - smoothstep(fitStart, 1f, Math.max(Math.abs(qx), Math.abs(qy)));
                out[y * w + x] = toArgbPre(c[0] * fit, c[1] * fit, c[2] * fit, c[3] * fit);
            }
        }
    }

    /** Porto de {@code orbGlassLiquidAnim} para o caminho glass (style 20). */
    private static float[] orbGlassLiquidAnim(float uvx, float uvy, float t, float[] u) {
        float[] out = new float[4];
        float rad = Math.max(u[IDX_RADIUS], 0.05f);
        float soft = u[IDX_EDGE_SOFTNESS];
        float glow = u[IDX_EDGE_GLOW];
        float contourRad = rad * glsContourScale(uvx, uvy, t, u[IDX_CONTOUR_DEFORM], u);
        float edgeSoftD = soft - 0.005f;
        float distToCenter = (float) Math.sqrt(uvx * uvx + uvy * uvy);

        // Fora do disco: apenas o halo (edge glow), tal como o original.
        if (distToCenter > contourRad * (1.01f + edgeSoftD)) {
            float halo = edgeGlowWeight(distToCenter, contourRad, soft, glow);
            float hr = clamp01(u[IDX_GLOW_COLOR] * halo);
            float hg = clamp01(u[IDX_GLOW_COLOR + 1] * halo);
            float hb = clamp01(u[IDX_GLOW_COLOR + 2] * halo);
            out[0] = hr;
            out[1] = hg;
            out[2] = hb;
            out[3] = Math.max(hr, Math.max(hg, hb));
            return out;
        }

        float px = uvx / contourRad;
        float py = uvy / contourRad;
        float pd = (float) Math.sqrt(px * px + py * py);
        float clearFa = 1f - smoothstep(GL_CLEAR_EA, GL_CLEAR_EB, pd);
        float edgeDepth = Math.max(1f - pd, 0f);

        // Normal do contorno (contour deform) — style 20 não refração extra (style 23).
        float[] n2 = glsContourNormal(uvx, uvy, rad, t, u[IDX_CONTOUR_DEFORM], u);
        float nx = n2[0];
        float ny = n2[1];

        // Perfil de refração da borda.
        float refractionWidth = 0.015f + 0.95f * clamp01(u[IDX_SHELL_MID_ALPHA]);
        float refractionT = edgeDepth / Math.max(refractionWidth, 0.001f);
        float refractionProfile = (float) Math.pow(glsRefractionProfile(refractionT), 0.68);
        float refractionAmount = 1.6f * clamp01(u[IDX_GLASS_OPACITY]) * refractionProfile;
        float rpx = px - nx * refractionAmount;
        float rpy = py - ny * refractionAmount;

        // Fluido BlueDrop amostrado 3x (dispersão cromática por canal).
        float channelSplit = 0.14f * clamp(u[IDX_GLOSS], 0f, 2f)
                * clamp01(u[IDX_GLASS_OPACITY]) * refractionProfile;
        float fr = blueDropFluid(rpx - nx * channelSplit, rpy - ny * channelSplit, t, u, 0);
        float fg = blueDropFluid(rpx, rpy, t, u, 1);
        float fb = blueDropFluid(rpx + nx * channelSplit, rpy + ny * channelSplit, t, u, 2);
        float fcolR = fr;
        float fcolG = fg;
        float fcolB = fb;

        // Saturação "clear" e base de canvas.
        float lum = fcolR * 0.213f + fcolG * 0.715f + fcolB * 0.072f;
        float satR = clamp01(lum + (fcolR - lum) * 1.22f);
        float satG = clamp01(lum + (fcolG - lum) * 1.22f);
        float satB = clamp01(lum + (fcolB - lum) * 1.22f);
        float mixA = 0.99f * clearFa;
        float colR = u[IDX_CANVAS_COLOR] * (1f - mixA) + satR * mixA;
        float colG = u[IDX_CANVAS_COLOR + 1] * (1f - mixA) + satG * mixA;
        float colB = u[IDX_CANVAS_COLOR + 2] * (1f - mixA) + satB * mixA;

        if (u[IDX_GLASS_ENABLED] > 0.5f) {
            // Casca de vidro: rim interno, dispersão fria/quente, sombra de borda.
            float surfaceWidth = 0.026f + 0.055f * clamp01(u[IDX_SHELL_EDGE_ALPHA]);
            float surfaceBand = (1f - smoothstep(0f, surfaceWidth, edgeDepth)) * clearFa;
            float opticalRim = (float) Math.pow(surfaceBand, 1.8f);
            float innerRimAlpha = opticalRim * u[IDX_GLASS_OPACITY] * 0.45f;
            colR = glsOver1(colR, u[IDX_SHELL_INNER], innerRimAlpha);
            colG = glsOver1(colG, u[IDX_SHELL_INNER + 1], innerRimAlpha);
            colB = glsOver1(colB, u[IDX_SHELL_INNER + 2], innerRimAlpha);

            // Direções de luz normalizadas (iguais às do shader original).
            float coolSplit = glsHighlightLobe(nx, ny, 0.8411785f, 0.5407576f, -0.32f, 1.8f);
            float warmSplit = glsHighlightLobe(nx, ny, -0.6222441f, -0.7828233f, -0.28f, 2.0f);
            float dispersion = opticalRim * clamp(u[IDX_GLOSS], 0f, 2f)
                    * (0.8f + 0.8f * u[IDX_SHELL_EDGE_ALPHA]);
            float dCool = dispersion * coolSplit;
            float dWarm = dispersion * warmSplit;
            colR = glsOver1(colR, u[IDX_SHELL_MID], dCool);
            colG = glsOver1(colG, u[IDX_SHELL_MID + 1], dCool);
            colB = glsOver1(colB, u[IDX_SHELL_MID + 2], dCool);
            colR = glsOver1(colR, u[IDX_SHELL_EDGE], dWarm);
            colG = glsOver1(colG, u[IDX_SHELL_EDGE + 1], dWarm);
            colB = glsOver1(colB, u[IDX_SHELL_EDGE + 2], dWarm);

            float edgeShadow = opticalRim * (0.015f + 0.15f * u[IDX_SHELL_EDGE_ALPHA])
                    * (0.15f + 0.85f * Math.max(nx * 0.45f + ny * -0.89f, 0f));
            colR *= 1f - edgeShadow;
            colG *= 1f - edgeShadow;
            colB *= 1f - edgeShadow;

            // Sheen (luz principal) e specular (luz de preenchimento).
            float key = opticalRim * glsHighlightLobe(nx, ny, -0.6816037f, 0.7317216f, 0.2f, 2.8f)
                    * clamp(u[IDX_SHEEN], 0f, 2f) * 1.4f;
            float fill = opticalRim * glsHighlightLobe(nx, ny, 0.7412984f, -0.6711756f, 0.4f, 3.6f)
                    * clamp(u[IDX_SHEEN], 0f, 2f);
            colR = glsOver1(colR, u[IDX_SHEEN_COLOR], key);
            colG = glsOver1(colG, u[IDX_SHEEN_COLOR + 1], key);
            colB = glsOver1(colB, u[IDX_SHEEN_COLOR + 2], key);
            colR = glsOver1(colR, u[IDX_SPEC_COLOR], fill);
            colG = glsOver1(colG, u[IDX_SPEC_COLOR + 1], fill);
            colB = glsOver1(colB, u[IDX_SPEC_COLOR + 2], fill);
        }

        // Alpha esférico + exposure + edge glow final.
        float ballA = 1f - smoothstep(0.99f - edgeSoftD, 1.01f + edgeSoftD, pd);
        colR = clamp01(colR * Math.max(u[IDX_EXPOSURE], 0f)) * ballA;
        colG = clamp01(colG * Math.max(u[IDX_EXPOSURE], 0f)) * ballA;
        colB = clamp01(colB * Math.max(u[IDX_EXPOSURE], 0f)) * ballA;

        float halo = edgeGlowWeight(distToCenter, contourRad, soft, glow);
        float fr2 = clamp01(colR + u[IDX_GLOW_COLOR] * halo);
        float fg2 = clamp01(colG + u[IDX_GLOW_COLOR + 1] * halo);
        float fb2 = clamp01(colB + u[IDX_GLOW_COLOR + 2] * halo);

        float emissionAlpha = Math.max(fr2, Math.max(fg2, fb2));
        float sphereAlpha = clamp01(Math.max(ballA, emissionAlpha));
        out[0] = fr2;
        out[1] = fg2;
        out[2] = fb2;
        out[3] = sphereAlpha;
        return out;
    }

    /**
     * Porto de {@code glsBlueDropFluid} — o fluido interior do orbe.
     * Devolve apenas o canal {@code channel} (0=R, 1=G, 2=B) do original,
     * porque a dispersão cromática só consome um canal por amostra.
     */
    private static float blueDropFluid(float px, float py, float t, float[] u, int channel) {
        float radial2 = clamp01(px * px + py * py);
        float depth = (float) Math.sqrt(Math.max(1f - radial2, 0f));
        // q = p * mix(0.72, 1.0, depth*0.62 + 0.38)
        float depthMix = lerp(0.72f, 1.0f, clamp01(depth * 0.62f + 0.38f));
        float qx = px * depthMix;
        float qy = py * depthMix;
        float rot = -0.24f + 0.06f * (float) Math.sin(t * 0.17f);
        float c = (float) Math.cos(rot);
        float s = (float) Math.sin(rot);
        float rx = c * qx - s * qy;
        float ry = s * qx + c * qy;

        float zoom = u[IDX_ZOOM];
        float scale = 1f + zoom * 1.12f;
        float blur = 0.012f + 0.006f * zoom;

        float[] fbm2 = new float[2];
        // driftA = lqFbm(q*1.28 + (t*0.095, -t*0.034), blur*1.28)
        lqFbm(rx * 1.28f + t * 0.095f, ry * 1.28f - t * 0.034f, blur * 1.28f, fbm2);
        float driftAx = fbm2[0];
        // driftB = lqFbm(rotate(q,1.08)*1.62 + (-t*0.042, t*0.078), blur*1.62)
        float c2 = (float) Math.cos(1.08f);
        float s2 = (float) Math.sin(1.08f);
        float r2x = c2 * rx - s2 * ry;
        float r2y = s2 * rx + c2 * ry;
        lqFbm(r2x * 1.62f - t * 0.042f, r2y * 1.62f + t * 0.078f, blur * 1.62f, fbm2);
        float driftBx = fbm2[0];

        float warp = u[IDX_WARP];
        float flowScale = 0.24f + warp * 0.1f;
        float fx = rx + (driftAx - 0.5f) * flowScale;
        float fy = ry + (driftBx - 0.5f) * flowScale;
        fx += (float) Math.sin(fy * 2.15f + t * 0.24f) * (0.035f + warp * 0.012f);
        fy += (float) Math.sin(fx * 1.38f - t * 0.18f) * (0.045f + warp * 0.01f);

        // body = lqFbm(flowed*scale + (t*0.025, -t*0.018), blur*scale)
        lqFbm(fx * scale + t * 0.025f, fy * scale - t * 0.018f, blur * scale, fbm2);
        float bodyX = fbm2[0];

        // marble = lqRidgeS(lqFbm(flowed*marbleScale + (2.7, -t*0.035), blur*marbleScale), 0.8 + sharp*0.46)
        float marbleScale = 1.72f + zoom * 0.9f;
        lqFbm(fx * marbleScale + 2.7f, fy * marbleScale - t * 0.035f, blur * marbleScale, fbm2);
        float marbleValue = fbm2[0];
        float marbleRough = fbm2[1];
        float k = 0.8f + u[IDX_SHARP] * 0.46f;
        float d = GL_GH * marbleRough;
        float marble = (lqRidge(marbleValue - d, k)
                + 4f * lqRidge(marbleValue, k)
                + lqRidge(marbleValue + d, k)) / 6f;

        float ridgeAmt = u[IDX_RIDGE_AMT];
        float value = clamp01(lerp(bodyX, bodyX * 0.62f + marble * 0.58f, ridgeAmt));
        // lqRamp devolve float3 — aqui só precisamos do canal 'channel'.
        float chan = rampChannel(value, channel, u);

        // Iluminação: luz direcional + profundidade esférica (neste canal).
        float invLen = 1f / Math.max((float) Math.sqrt(
                px * px + py * py + depth * depth), 1e-6f);
        float sx = px * invLen;
        float sy = py * invLen;
        float sz = depth * invLen;
        // direction = normalize((-0.48, 0.62, 0.92))
        float dlen = (float) Math.sqrt(1.4372f);
        float dot = (sx * -0.48f + sy * 0.62f + sz * 0.92f) / dlen;
        float light = (float) Math.pow(Math.max(dot, 0f), 3.2f);
        float hl = light * (0.035f + 0.05f * u[IDX_SHADE]);
        float hlx = u[IDX_HIGHLIGHT + channel];
        chan = chan + (hlx - chan) * hl;

        chan *= 0.74f + 0.26f * depth;

        // glsFinishPresetFluid (neste canal)
        float shade = u[IDX_SHADE];
        float hl2 = shade * 0.22f * smoothstep(0.15f, 1.15f, px * -0.32f + py * 0.78f);
        chan = chan + (hlx - chan) * hl2;
        chan *= 1f - shade * 0.34f * smoothstep(-0.1f, 1.2f, px * 0.45f + py * -0.62f);
        chan *= 1f - shade * 0.22f * smoothstep(0.72f, 1.08f, (float) Math.sqrt(px * px + py * py));

        return clamp01(chan);
    }

    /**
     * Rampa de 4 cores do original ({@code lqRamp}, paletteCount 0), um canal
     * RGB de cada vez: {@code comp} = 0 (R), 1 (G) ou 2 (B).
     */
    private static float rampChannel(float v, int comp, float[] u) {
        float m1 = smoothstep(0f, 0.45f, v);
        float c = lerp(u[IDX_COLOR_A + comp], u[IDX_COLOR_B + comp], m1);
        float m2 = smoothstep(0.38f, 0.72f, v);
        c = lerp(c, u[IDX_COLOR_C + comp], m2);
        float m3 = smoothstep(0.68f, 1f, v);
        c = lerp(c, u[IDX_COLOR_D + comp], m3);
        return c;
    }

    // ------------------------------------------------------------------
    // Primitivas do shader
    // ------------------------------------------------------------------

    private static float lqHash(float x, float y) {
        float hx = fract(x * 123.34f);
        float hy = fract(y * 456.21f);
        float d = hx * (hx + 45.32f) + hy * (hy + 45.32f);
        return fract((hx + d) * (hy + d));
    }

    private static float lqNoise(float px, float py) {
        float ix = (float) Math.floor(px);
        float iy = (float) Math.floor(py);
        float fx = px - ix;
        float fy = py - iy;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float h00 = lqHash(ix, iy);
        float h10 = lqHash(ix + 1f, iy);
        float h01 = lqHash(ix, iy + 1f);
        float h11 = lqHash(ix + 1f, iy + 1f);
        return lerp(lerp(h00, h10, fx), lerp(h01, h11, fx), fy);
    }

    /** FBM de 5 oitavas; out2 = (valor, rugosidade), como no original. */
    private static void lqFbm(float px, float py, float bs, float[] out2) {
        float e = -6f * bs * bs;
        float s = 0f;
        float a = 0.5f;
        float vr = 0f;
        float m = 0f;
        float g = 1f;
        for (int i = 0; i < 5; i++) {
            float b = (float) Math.exp(e * g);
            s += a * (0.5f + b * (lqNoise(px, py) - 0.5f));
            vr += a * a * (1f - b * b);
            m += a;
            a *= 0.5f;
            g *= GL_KG;
            float nx = 0.8f * px - 0.6f * py;
            float ny = 0.6f * px + 0.8f * py;
            px = nx * 2.03f;
            py = ny * 2.03f;
        }
        out2[0] = s / m;
        out2[1] = GL_KR * (float) Math.sqrt(vr) / m;
    }

    private static float lqRidge(float v, float k) {
        return (float) Math.pow(clamp01(1f - Math.abs(v * 2f - 1f)), k);
    }

    /** Perfil de refração da borda ({@code glsRefractionProfile}). */
    private static float glsRefractionProfile(float tIn) {
        float depth = clamp01(tIn);
        float circular = (float) Math.sqrt(Math.max(1f - (1f - depth) * (1f - depth), 0f));
        return 1f - circular;
    }

    /** Lobe de brilho direcional ({@code glsHighlightLobe}). */
    private static float glsHighlightLobe(float nx, float ny, float dx, float dy,
                                          float cut, float power) {
        float angular = clamp01((nx * dx + ny * dy - cut) / Math.max(1f - cut, 0.001f));
        return (float) Math.pow(angular, power);
    }

    /** Blend "over" ({@code glsOver}) para um canal. */
    private static float glsOver1(float dst, float src, float alpha) {
        float k = clamp01(alpha);
        return src * k + dst * (1f - k);
    }

    /** Peso do edge glow fora/na borda do orbe ({@code mfEdgeGlow}). */
    private static float edgeGlowWeight(float r, float rad, float soft, float glow) {
        if (glow <= 0f) {
            return 0f;
        }
        float s = Math.max(soft, 0.0005f);
        float outside = smoothstep(rad - s, rad + s, r);
        return glow * (float) Math.exp(-Math.max(r - rad, 0f) * 11f) * outside;
    }

    /** Onda de contorno genérica ({@code glsContourWave}, style != 19). */
    private static float[] glsContourWave(float angle, float t) {
        float w = 0.52f * (float) Math.sin(angle * 3f + t * 0.62f)
                + 0.31f * (float) Math.sin(angle * 5f - t * 0.41f + 1.7f)
                + 0.17f * (float) Math.sin(angle * 2f + t * 0.23f + 3.1f);
        float slope = 1.56f * (float) Math.cos(angle * 3f + t * 0.62f)
                + 1.55f * (float) Math.cos(angle * 5f - t * 0.41f + 1.7f)
                + 0.34f * (float) Math.cos(angle * 2f + t * 0.23f + 3.1f);
        return new float[]{w, slope};
    }

    /** Intensidade da deformação de contorno ({@code glsContourStrength}). */
    private static float glsContourStrength(float[] u) {
        if (u[IDX_STYLE] >= 18.5f) {
            return 0.11f;
        }
        return u[IDX_STYLE] >= 15.5f ? 0.16f : 0.09f;
    }

    private static float glsContourScale(float uvx, float uvy, float t, float amount, float[] u) {
        if (amount <= 0f) {
            return 1f;
        }
        float[] wave = glsContourWave((float) Math.atan2(uvy, uvx), t);
        return 1f + clamp01(amount) * glsContourStrength(u) * wave[0];
    }

    private static float[] glsContourNormal(float uvx, float uvy, float rad, float t,
                                            float amount, float[] u) {
        float distance = (float) Math.sqrt(uvx * uvx + uvy * uvy);
        if (distance <= 0.0001f) {
            return new float[]{0f, 0f};
        }
        float rdx = uvx / distance;
        float rdy = uvy / distance;
        float[] wave = glsContourWave((float) Math.atan2(uvy, uvx), t);
        float slope = clamp01(amount) * glsContourStrength(u) * wave[1];
        // tangent = (-rdy, rdx); normal = normalize(radial - tangent*(rad*slope/distance))
        float k = rad * slope / distance;
        float nx = rdx - (-rdy) * k;
        float ny = rdy - (rdx) * k;
        float len = (float) Math.sqrt(nx * nx + ny * ny);
        if (len < 1e-6f) {
            return new float[]{0f, 0f};
        }
        return new float[]{nx / len, ny / len};
    }

    // ------------------------------------------------------------------
    // sRGB (transições entre seeds, tal como no original)
    // ------------------------------------------------------------------

    private static float srgbToLinear(float v) {
        return v <= 0.04045f ? v / 12.92f
                : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }

    private static float linearToSrgb(float v) {
        return v <= 0.0031308f ? v * 12.92f
                : 1.055f * (float) Math.pow(v, 1.0 / 2.4) - 0.055f;
    }

    private static float mixSrgb(float from, float to, float progress) {
        float lf = srgbToLinear(from);
        float lt = srgbToLinear(to);
        return linearToSrgb(lf + (lt - lf) * progress);
    }

    // ------------------------------------------------------------------
    // Aritmética base
    // ------------------------------------------------------------------

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3f - 2f * t);
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    private static int toArgbPre(float r, float g, float b, float a) {
        int ai = (int) clamp(a * 255f + 0.5f, 0f, 255f);
        if (ai == 0) {
            return 0;
        }
        // Premultiplicado: cada canal RGB não pode exceder o alpha.
        int ri = (int) clamp(Math.min(r, a) * 255f + 0.5f, 0f, 255f);
        int gi = (int) clamp(Math.min(g, a) * 255f + 0.5f, 0f, 255f);
        int bi = (int) clamp(Math.min(b, a) * 255f + 0.5f, 0f, 255f);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }
}
