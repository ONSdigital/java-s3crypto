package dp.s3crypto.stream;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class Test {


    public static void main(String[] args) throws Exception {
        String s = "If you like to gamble, I tell you I'm your man\n" +
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

        try (InputStream in2 = new WrappedInputStream(new ByteArrayInputStream(s.getBytes()), 500);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in2))) {

            boolean done = false;
            StringBuilder builder = new StringBuilder("\n");

            while (!done) {
                int n = in2.read();
                if (n == -1) {
                    done = true;
                    continue;
                }
                builder.append((char) n);
            }
            builder.append("\n");
            System.out.println(builder.toString());

/*            do {
                line = reader.readLine();
                if (line == null) {
                    done = true;
                    continue;
                }

                System.out.println(line);

            } while (!done);*/

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
