package domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class UserProfile {

    private String fullName = "";
    private String preferredName = "";
    private LocalDate birthDate = null;
    private List<String> occupations = new ArrayList<>();
    private String aboutYou = "";

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPreferredName() {
        return preferredName;
    }

    public void setPreferredName(String preferredName) {
        this.preferredName = preferredName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public List<String> getOccupations() {
        return occupations;
    }

    public void setOccupations(List<String> occupations) {
        this.occupations = occupations != null ? occupations : new ArrayList<>();
    }

    public String getAboutYou() {
        return aboutYou;
    }

    public void setAboutYou(String aboutYou) {
        this.aboutYou = aboutYou;
    }

    public boolean isEmpty() {
        return (fullName == null || fullName.isBlank()) &&
                (preferredName == null || preferredName.isBlank()) &&
                birthDate == null &&
                occupations.isEmpty() &&
                (aboutYou == null || aboutYou.isBlank());
    }

    @Override
    public String toString() {
        return "UserProfile{" +
                "fullName='" + fullName + '\'' +
                ", preferredName='" + preferredName + '\'' +
                ", birthDate=" + birthDate +
                ", occupations=" + occupations +
                ", aboutYou='" + aboutYou + '\'' +
                '}';
    }
}