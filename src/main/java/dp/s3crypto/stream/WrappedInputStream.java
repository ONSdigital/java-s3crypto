package dp.s3crypto.stream;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class WrappedInputStream extends InputStream implements Closeable {

    private InputStream wrappedInputStream;
    private byte[] current;
    private int index;
    private int endIndex;
    private int chunkSize;
    private boolean isLast;

    public WrappedInputStream(InputStream is, int chunkSize) {
        this.wrappedInputStream = is;
        this.index = 0;
        this.chunkSize = chunkSize;
        this.endIndex = chunkSize;
        this.isLast = false;
    }

    public int read(byte[] b) throws IOException {
        System.out.println("==> loading next chunk from wrapped reader");
        return this.wrappedInputStream.read(b);
    }

    @Override
    public void close() throws IOException {
        this.wrappedInputStream.close();
    }

    @Override
    public int read() throws IOException {
        if (this.current == null) {
            byte[] newChunk = new byte[this.chunkSize];
            int n = read(newChunk);
            this.index = 0;

            if (n == -1) {
                this.isLast = true;
                return n;
            }

            if (n < chunkSize) {
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

            byte[] newChunk = new byte[this.chunkSize];
            int n = read(newChunk);
            this.index = 0;

            if (n == -1) {
                this.isLast = true;
                return n;
            }

            if (n < chunkSize) {
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
}


