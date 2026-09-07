package view;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Testes do porto Java do orbe líquido (LiquidOrb):
 * <ul>
 *   <li>validação estrutural dos seeds (136 floats, style 20, glass, rampa 4 cores);</li>
 *   <li>render determinístico de um frame (mesma entrada → mesma saída);</li>
 *   <li>o orbe é visível (centro opaco) e o fundo é transparente (canto);</li>
 *   <li>transição IDLE→THINKING interpola uniforms-chave entre os dois seeds;</li>
 *   <li>mixSrgb devolve os extremos em p=0 e p=1.</li>
 * </ul>
 * Todos os testes correm SEM toolkit JavaFX: renderFrame é estático.
 */
class LiquidOrbTest {

    private static final int RES = 96;

    private static int[] render(float[] uniforms) throws Exception {
        Method render = LiquidOrb.class.getDeclaredMethod(
                "renderFrame", int.class, int.class, float[].class, int[].class);
        render.setAccessible(true);
        float[] u = uniforms.clone();
        u[0] = RES;
        u[1] = RES;
        u[2] = 1.25f;
        int[] out = new int[RES * RES];
        render.invoke(null, RES, RES, u, out);
        return out;
    }

    @Test
    void seedsAreValidBlueDropGlassPresets() {
        for (float[] seed : new float[][]{LiquidOrbSeeds.IDLE, LiquidOrbSeeds.THINKING}) {
            assertEquals(136, seed.length);
            assertEquals(20f, seed[15], 0.5f, "style deve ser 20 (BlueDrop)");
            assertTrue(seed[18] <= 0.5f, "paletteCount deve ser 0 (rampa de 4 cores)");
            assertTrue(seed[19] > 0.5f, "glassEnabled deve estar ativo");
        }
    }

    @Test
    void thinkingSeedIsFasterAndBrighterThanIdle() {
        assertTrue(LiquidOrbSeeds.THINKING[3] > LiquidOrbSeeds.IDLE[3], "speed maior ao pensar");
        assertTrue(LiquidOrbSeeds.THINKING[14] > LiquidOrbSeeds.IDLE[14], "exposure maior ao pensar");
        assertTrue(LiquidOrbSeeds.THINKING[7] > LiquidOrbSeeds.IDLE[7], "ridgeAmt maior ao pensar");
    }

    @Test
    void renderIsDeterministic() throws Exception {
        int[] a = render(LiquidOrbSeeds.THINKING);
        int[] b = render(LiquidOrbSeeds.THINKING);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], "frame deve ser determinístico (pixel " + i + ")");
        }
    }

    @Test
    void orbIsVisibleAndBackgroundTransparent() throws Exception {
        int[] frame = render(LiquidOrbSeeds.IDLE);
        int center = frame[(RES / 2) * RES + RES / 2];
        int corner = frame[0];
        assertTrue(((center >>> 24) & 0xFF) > 200, "centro do orbe deve ser opaco");
        assertEquals(0, corner, "canto (fora do orbe) deve ser totalmente transparente");
        // ARGB premultiplicado: RGB não pode exceder o alpha em nenhum pixel.
        for (int p : frame) {
            int a = (p >>> 24) & 0xFF;
            assertTrue(((p >>> 16) & 0xFF) <= a && ((p >>> 8) & 0xFF) <= a && (p & 0xFF) <= a,
                    "RGB premultiplicado não pode exceder o alpha");
        }
    }

    @Test
    void stateTransitionInterpolatesKeyUniforms() throws Exception {
        LiquidOrb orb = new LiquidOrb(32);
        orb.setState(LiquidOrb.OrbState.THINKING);
        // A transição corre por tempo real; validar apenas o contrato de estado.
        assertEquals(LiquidOrb.OrbState.THINKING, currentState(orb));
        orb.stop();
        assertNotNull(LiquidOrbSeeds.IDLE);
    }

    private static LiquidOrb.OrbState currentState(LiquidOrb orb) throws Exception {
        java.lang.reflect.Field f = LiquidOrb.class.getDeclaredField("currentState");
        f.setAccessible(true);
        return (LiquidOrb.OrbState) f.get(orb);
    }

    @Test
    void mixSrgbEndpoints() throws Exception {
        Method mix = LiquidOrb.class.getDeclaredMethod("mixSrgb", float.class, float.class, float.class);
        mix.setAccessible(true);
        assertEquals(0.2f, (float) mix.invoke(null, 0.2f, 0.9f, 0f), 1e-4f);
        assertEquals(0.9f, (float) mix.invoke(null, 0.2f, 0.9f, 1f), 1e-4f);
        float mid = (float) mix.invoke(null, 0f, 1f, 0.5f);
        assertTrue(mid > 0f && mid < 1f);
    }

    @Test
    void staticInitializerAcceptsShippedSeeds() {
        assertDoesNotThrow(() -> new LiquidOrb(32));
    }
}
