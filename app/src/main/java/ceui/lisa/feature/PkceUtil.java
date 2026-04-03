package ceui.lisa.feature;

import android.util.Base64;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class PkceUtil {

    private static PKCEItem sPkce;

    public static PKCEItem getPkce() {
        if (sPkce == null) {
            try {
                String verify = generateCodeVerifier();
                String challenge = generateCodeChallenge(verify);
                sPkce = new PKCEItem(verify, challenge);
            } catch (Exception e) {
                e.printStackTrace();
                sPkce = new PKCEItem(
                        "-29P7XEuFCNdG-1aiYZ9tTeYrABWRHxS9ZVNr6yrdcI",
                        "usItTkssolVsmIbxrf0o-O_FsdvZFANVPCf9jP4jP_0");
            }
        }
        return sPkce;
    }

    public static String generateCodeVerifier() throws UnsupportedEncodingException {
        SecureRandom secureRandom = new SecureRandom();
        byte[] codeVerifier = new byte[32];
        secureRandom.nextBytes(codeVerifier);
        return Base64.encodeToString(codeVerifier, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public static String generateCodeChallenge(String codeVerifier) throws UnsupportedEncodingException, NoSuchAlgorithmException {
        byte[] bytes = codeVerifier.getBytes(StandardCharsets.US_ASCII);
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        messageDigest.update(bytes, 0, bytes.length);
        byte[] digest = messageDigest.digest();
        return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
