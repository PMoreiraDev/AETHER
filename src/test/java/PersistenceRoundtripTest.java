import domain.UserProfile;
import persistence.SqliteUserProfileRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class PersistenceRoundtripTest {
    public static void main(String[] args) throws Exception {
        // Use a temp directory for the test database
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("aether-test");
        System.setProperty("aether.data.dir", tmpDir.toString());
        
        var repo = new SqliteUserProfileRepository();
        
        UserProfile profile = new UserProfile();
        profile.setFullName("Test User");
        profile.setPreferredName("Test");
        profile.setBirthDate(LocalDate.of(1990, 5, 15));
        profile.setOccupations(List.of("Developer", "Designer"));
        profile.setAbout("About me text");
        profile.setAiContext("Additional AI context");
        profile.setStudies("Computer Science, University of Porto");
        profile.setExperience("5 years as software developer");
        profile.setSkills("Java, Python, SQL, Design");
        profile.setInterests("Reading, hiking, gaming");
        profile.setObjectives("Learn AI, build great apps");
        profile.setPreferences("Prefers concise answers, dark mode");
        profile.setProjects("AETHER, personal blog");
        profile.setWorkStyle("Focused, async communication");
        profile.setProfileSummary("A developer passionate about AI");
        profile.setInferredContext("Likely interested in technology trends");
        profile.setSuggestedUpdates("Consider adding education field");
        
        repo.save(profile);
        System.out.println("Save: OK");
        
        Optional<UserProfile> opt = repo.find();
        assert opt.isPresent() : "Profile not found after save";
        UserProfile loaded = opt.get();
        
        assert loaded.getFullName().equals("Test User") : "fullName mismatch: " + loaded.getFullName();
        assert loaded.getPreferredName().equals("Test") : "preferredName mismatch";
        assert loaded.getBirthDate().equals(LocalDate.of(1990, 5, 15)) : "birthDate mismatch";
        assert loaded.getOccupations().size() == 2 : "occupations size mismatch";
        assert loaded.getOccupations().get(0).equals("Developer") : "occupation[0] mismatch";
        assert loaded.getAbout().equals("About me text") : "about mismatch";
        assert loaded.getAiContext().equals("Additional AI context") : "aiContext mismatch";
        assert loaded.getStudies().equals("Computer Science, University of Porto") : "studies mismatch: " + loaded.getStudies();
        assert loaded.getExperience().equals("5 years as software developer") : "experience mismatch: " + loaded.getExperience();
        assert loaded.getSkills().equals("Java, Python, SQL, Design") : "skills mismatch: " + loaded.getSkills();
        assert loaded.getInterests().equals("Reading, hiking, gaming") : "interests mismatch: " + loaded.getInterests();
        assert loaded.getObjectives().equals("Learn AI, build great apps") : "objectives mismatch: " + loaded.getObjectives();
        assert loaded.getPreferences().equals("Prefers concise answers, dark mode") : "preferences mismatch: " + loaded.getPreferences();
        assert loaded.getProjects().equals("AETHER, personal blog") : "projects mismatch: " + loaded.getProjects();
        assert loaded.getWorkStyle().equals("Focused, async communication") : "workStyle mismatch: " + loaded.getWorkStyle();
        assert loaded.getProfileSummary().equals("A developer passionate about AI") : "profileSummary mismatch: " + loaded.getProfileSummary();
        assert loaded.getInferredContext().equals("Likely interested in technology trends") : "inferredContext mismatch: " + loaded.getInferredContext();
        assert loaded.getSuggestedUpdates().equals("Consider adding education field") : "suggestedUpdates mismatch: " + loaded.getSuggestedUpdates();
        
        System.out.println("All structured fields persisted correctly: OK");
        
        assert loaded.getDisplaySummary().equals("A developer passionate about AI") : "displaySummary mismatch: " + loaded.getDisplaySummary();
        System.out.println("getDisplaySummary (from profileSummary): OK");
        
        profile.setProfileSummary(null);
        assert profile.getDisplaySummary().equals("About me text") : "displaySummary fallback to about failed";
        System.out.println("getDisplaySummary (fallback to about): OK");
        
        profile.setProfileSummary(null);
        profile.setAboutYou(null);
        assert profile.getDisplaySummary().equals("Developer, Designer") : "displaySummary fallback to occupations failed: " + profile.getDisplaySummary();
        System.out.println("getDisplaySummary (fallback to occupations): OK");
        
        System.out.println("\n=== ALL PERSISTENCE ROUNDTRIP TESTS PASSED ===");
    }
}
