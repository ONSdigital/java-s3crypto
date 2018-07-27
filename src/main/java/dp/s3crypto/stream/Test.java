package dp.s3crypto.stream;

import dp.s3crypto.S3CryptoInputStream3;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Test {

    static final String CONTENT = "If you like to gamble, I tell you I'm your man\n" +
            "You win some, lose some, all the same to me\n" +
            "The pleasure is to play, makes no difference what you say\n" +
            "I don't share your greed, the only card I need is the Ace of Spades\n" +
            "The Ace of Spades\n" +
            "Playing for the high one, dancing with the devil\n" +
            "Going with the flow, it's all a game to me\n" +
            "Seven or eleven, snake eyes watching you\n" +
            "Double up or quit, double stake or split, the Ace of Spades\n" +
            "The Ace of Spades\n" +
            "You know I'm born to lose, and gambling's for fools\n" +
            "But that's the way I like it baby\n" +
            "I don't wanna live for ever\n" +
            "And don't forget the joker!\n" +
            "Pushing up the ante, I know you gotta see me\n" +
            "Read 'em and weep, the dead man's hand again\n" +
            "I see it in your eyes, take one look and die\n" +
            "The only thing you see, you know it's gonna be the Ace of Spades\n" +
            "The Ace of Spades";

    public static void main(String[] args) throws Exception {
        byte[] pskBytes = Files.readAllBytes(Paths.get("/Users/dave/Desktop/s3crypto/psk.txt"));
        File encryptedFile = Paths.get("/Users/dave/Desktop/s3crypto/encrypted.csv").toFile();
        File decryptedFile = Paths.get("/Users/dave/Desktop/s3crypto/out.csv").toFile();


        try (InputStream fis = new FileInputStream(encryptedFile);
             InputStream crypto = new S3CryptoInputStream3(fis, 5 * 1024 * 1024, pskBytes);
             BufferedReader reader = new BufferedReader(new InputStreamReader(crypto));
             BufferedWriter writer = new BufferedWriter(new FileWriter(decryptedFile, true))
        ) {
            String line = null;
            while ((line = reader.readLine()) != null) {
                writer.append(line).append("\n");
            }
        }
    }
}
