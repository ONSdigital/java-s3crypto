package dp.s3crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class S3CryptoInputStream2 extends InputStream implements Closeable {

    private InputStream wrappedInputStream;
    private byte[] current;
    private int index;
    private int endIndex;
    private int blockSize;
    private boolean isLast;
    private byte[] psk;

    public S3CryptoInputStream2(InputStream is, int blockSize, byte[] psk) {
        this.wrappedInputStream = is;
        this.index = 0;
        this.blockSize = blockSize;
        this.endIndex = blockSize;
        this.isLast = false;
        this.psk = psk;
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

    public int read(byte[] b) throws IOException {
        byte[] encrypted = new byte[blockSize];
        int n = wrappedInputStream.read(encrypted); // read the next block of encrypted data.

        if (n == -1) {
            return n; // its done so return -1.
        }


        byte[] decrypted = decryptObjectContent(psk, encrypted);
        for (int i = 0; i < b.length; i++) {
            b[i] = decrypted[i];
        }
        return n;
    }

    @Override
    public int read() throws IOException {
        if (this.current == null) {
            byte[] newChunk = new byte[this.blockSize];
            int n = read(newChunk);
            this.index = 0;

            if (n == -1) {
                this.isLast = true;
                return n;
            }

            if (n < blockSize) {
                newChunk = Arrays.copyOfRange(newChunk, 0, n);
                this.endIndex = newChunk.length;
                this.current = newChunk;
                this.isLast = true;
            }

            this.current = newChunk;
        }

        if (this.index >= endIndex) {

            if (isLast) {
                return -1;
            }

            byte[] newChunk = new byte[this.blockSize];
            int n = read(newChunk);
            this.index = 0;

            if (n == -1) {
                this.isLast = true;
                return n;
            }

            if (n < blockSize) {
                System.out.println("==> loaded chunk smaller than chunk limit");
                newChunk = Arrays.copyOfRange(newChunk, 0, n);
                this.endIndex = newChunk.length;
                this.current = newChunk;
                this.isLast = true;
            }

            this.current = newChunk;
        }

        byte b = this.current[index];
        this.index++;
        return b;
    }


    @Override
    public void close() throws IOException {
        this.wrappedInputStream.close();
    }
}


