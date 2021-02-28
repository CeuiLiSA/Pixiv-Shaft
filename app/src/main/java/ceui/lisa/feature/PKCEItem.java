package ceui.lisa.feature;

public class PKCEItem {

    private final String verify;
    private final String challenge;

    public PKCEItem(String verify, String challenge) {
        this.verify = verify;
        this.challenge = challenge;
    }

    public String getVerify() {
        return verify;
    }

    public String getChallenge() {
        return challenge;
    }
}
