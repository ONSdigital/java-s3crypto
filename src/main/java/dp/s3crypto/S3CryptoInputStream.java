package dp.s3crypto;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class S3CryptoInputStream extends InputStream implements Closeable {

    private static final int SIZE = 5 * 1024 * 1024;

    private InputStream parentInputStream;
    private byte[] currChunk;
    private boolean lastChunk;
    private byte[] psk;

    public S3CryptoInputStream(InputStream is, byte[] psk) {
        this.parentInputStream = is;
        this.psk = psk;
    }

    public int read(byte[] b) throws IOException {
        if (this.lastChunk && currChunk.length == 0) {
            return -1;
        }

        if (currChunk == null || this.currChunk.length == 0) {
            byte[] encryptedCurrentChunk = new byte[SIZE];

            int n = this.parentInputStream.read(encryptedCurrentChunk);
            if (n == -1) {
                return n;
            }
            if (n < SIZE) {
                this.lastChunk = true;
            }

            this.currChunk = decryptObjectContent(psk, encryptedCurrentChunk);
        }

        int n = 0;
        if (this.currChunk.length >= b.length) {
            b = Arrays.copyOf(this.currChunk, b.length);
            n = b.length;
            this.currChunk = Arrays.copyOfRange(this.currChunk, b.length, this.currChunk.length);
        } else {
            b = Arrays.copyOf(this.currChunk, this.currChunk.length);
            n = this.currChunk.length;
            this.currChunk = null;
        }

        return n;
    }

    @Override
    public void close() throws IOException {
        this.parentInputStream.close();
    }

    private byte[] decryptObjectContent(byte[] psk, byte[] encrypted) throws IOException {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(psk, "AES");
            Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
            IvParameterSpec ivParameterSpec = new IvParameterSpec(psk);

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec);

            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            System.out.println(e);
            throw new IOException(e);
        }
    }

    @Override
    public int read() throws IOException {
        return this.parentInputStream.read();
    }

}
