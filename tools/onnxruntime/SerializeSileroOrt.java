import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

/** Serializes the pinned float32 Silero model with the full ONNX Runtime. */
public final class SerializeSileroOrt {
    private static final String INPUT_SHA256 =
        "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3";

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            text.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return text.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected input.onnx and output.ort");
        byte[] original = Files.readAllBytes(Path.of(args[0]));
        if (!sha256(original).equals(INPUT_SHA256)) throw new IllegalStateException("Unexpected input model");
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        if (!environment.getVersion().equals("1.24.3")) throw new IllegalStateException("Expected ONNX Runtime 1.24.3");
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            options.addConfigEntry("session.save_model_format", "ORT");
            options.setOptimizedModelFilePath(args[1]);
            try (OrtSession session = environment.createSession(original, options)) {
                // Session creation serializes the optimized graph; inference is not required.
            }
        }
        String actual = sha256(Files.readAllBytes(Path.of(args[1])));
        System.out.println(actual);
    }
}
