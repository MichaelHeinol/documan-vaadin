package de.documan.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import de.documan.config.DocuManProperties;

/** Verschlüsselt Postfach-Passwörter mit einem aus SECRET_KEY abgeleiteten Schlüssel. */
@Component
public class SecretBox {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final DocuManProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(DocuManProperties properties) {
        this.properties = properties;
    }

    private SecretKeySpec key() throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Leer, wenn SECRET_KEY geändert wurde: Das Passwort muss dann neu eingegeben werden. */
    public String decrypt(String encrypted) {
        if (encrypted.isEmpty()) {
            return "";
        }
        try {
            byte[] data = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, data, 0, NONCE_BYTES));
            return new String(cipher.doFinal(data, NONCE_BYTES, data.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException undecryptable) {
            return "";
        }
    }
}
