package dp.s3crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class S3CryptoInputStream3 extends InputStream implements Closeable {

    private InputStream wrappedInputStream;
    private ByteArrayInputStream decryptedBytes;
    private int blockSize;
    private boolean isLast;
    private byte[] psk;

    public S3CryptoInputStream3(InputStream s3EncryptedInputStream, int blockSize, byte[] psk) {
        this.wrappedInputStream = s3EncryptedInputStream;
        this.blockSize = blockSize;
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
        System.out.println("==> reading block on wrapped stream");
        if (isLast) {
            return -1;
        }

        byte[] s3EncryptedBytes = new byte[blockSize];
        int n = wrappedInputStream.read(s3EncryptedBytes); // read the next block of encrypted data.

        if (n == -1) {
            return n; // its done so return -1.
        }

        if (n < blockSize) { // mark as last block and chop off trailing 0 values.
            isLast = true;
            s3EncryptedBytes = Arrays.copyOfRange(s3EncryptedBytes, 0, n);
        }

        if (decryptedBytes != null) {
            decryptedBytes.close(); // dont think close is necessary for bytearrayinputstream but better to be safe.
        }

        decryptedBytes = new ByteArrayInputStream(decryptObjectContent(psk, s3EncryptedBytes)); // decrypt it.
        return n; // return number of bytes read
    }

    @Override
    public int read() throws IOException {
        if (decryptedBytes == null) {
            byte[] next = new byte[blockSize];
            int n = read(next);

            if (n == -1) {
                return n;
            }
        }

        int n = decryptedBytes.read();
        if (n == -1) { // current block is empty...
            byte[] next = new byte[blockSize];
            if (read(next) == -1) { // attempt to read in another block.
                isLast = true;
                return -1; // its done.
            }
            return decryptedBytes.read();
        }
        return n;
    }


    @Override
    public void close() throws IOException {
        this.decryptedBytes.close();
        this.wrappedInputStream.close();
    }
}


