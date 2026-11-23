package com.ccrstech.openandroiddex.launcher;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The email password ("Email me when an address changes", {@link
 * EmailNotice}), sealed with a key that never leaves the phone: AES-256-GCM
 * under a key of the Android Keystore, which the phone's secure hardware holds
 * where it has some. What is written to the app's files is the sealed bytes
 * alone. A copy of the files (a backup, a new phone) cannot be opened without
 * the key, and the key goes nowhere: there the password is typed again.
 *
 * <p>Not tied to the screen lock: the email that matters most goes while the
 * phone is locked, at night, after a restart, and a key that needs the phone
 * unlocked ({@code setUnlockedDeviceRequired}) would refuse it then.
 *
 * <p>The tests on a computer have no Android Keystore; they put a sealer of
 * their own in {@link #sealer}.
 */
final class EmailSecret {

    private EmailSecret() {
    }

    /** What seals and opens; the Keystore's on a phone. */
    interface Sealer {
        byte[] seal(byte[] plain) throws Exception;

        /** The plain bytes, or an exception: wrong key, changed bytes, no key. */
        byte[] open(byte[] sealed) throws Exception;

        /** The key goes: whatever it sealed can never be opened again. */
        void forget();
    }

    static volatile Sealer sealer = new Keystore();

    /** The format's first byte, so a later one can be told apart. */
    private static final byte VERSION = 1;
    /** Bound into every seal, so these bytes open only as what they are. */
    private static final byte[] PURPOSE = "LinuxOnDroid email password v1".getBytes(StandardCharsets.US_ASCII);

    /** AES-256-GCM in the Android Keystore, the IV the Keystore's own. */
    static final class Keystore implements Sealer {
        static final String ALIAS = "linuxondroid-email-password";

        private static SecretKey key(boolean make) throws Exception {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            Key k = ks.getKey(ALIAS, null);
            if (k instanceof SecretKey) return (SecretKey) k;
            if (!make) throw new IllegalStateException("no key");
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return g.generateKey();
        }

        @Override
        public byte[] seal(byte[] plain) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(true));
            c.updateAAD(PURPOSE);
            byte[] iv = c.getIV();
            byte[] body = c.doFinal(plain);
            byte[] out = new byte[2 + iv.length + body.length];
            out[0] = VERSION;
            out[1] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 2, iv.length);
            System.arraycopy(body, 0, out, 2 + iv.length, body.length);
            return out;
        }

        @Override
        public byte[] open(byte[] sealed) throws Exception {
            if (sealed == null || sealed.length < 3 || sealed[0] != VERSION) throw new IllegalArgumentException("not sealed here");
            int ivLen = sealed[1] & 0xff;
            if (ivLen < 12 || sealed.length < 2 + ivLen + 16) throw new IllegalArgumentException("too short");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 2, 2 + ivLen)));
            c.updateAAD(PURPOSE);
            return c.doFinal(sealed, 2 + ivLen, sealed.length - 2 - ivLen);
        }

        @Override
        public void forget() {
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                ks.deleteEntry(ALIAS);
            } catch (Exception e) {
                DexLog.warn("email", "cannot remove the password's key: " + e.getClass().getSimpleName());
            }
        }
    }
}
