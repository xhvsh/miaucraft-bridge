import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

/**
 * Release signing for the plugin's self-updater (Ed25519, JDK only).
 *
 *   java dev/ReleaseSigner.java gen  <private-key-file>
 *       Creates a new key pair. The private key goes to the file (keep it OUT of
 *       the repository and back it up: without it no update can be published),
 *       the public key is printed for UpdateSignature.RELEASE_PUBLIC_KEY.
 *
 *   java dev/ReleaseSigner.java sign <private-key-file> <version> <sha256>
 *       Prints the Base64 signature that belongs in plugin-update.json.
 *
 * The signed bytes must match UpdateSignature.message(): a domain tag, the
 * version and the lowercase sha256, newline separated.
 */
public final class ReleaseSigner {

  public static void main(String[] args) throws Exception {
    if (args.length >= 2 && args[0].equals("gen")) {
      Path file = Path.of(args[1]);
      if (Files.exists(file)) {
        throw new IllegalStateException("refusing to overwrite existing key file " + file);
      }
      KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.writeString(file, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
      System.out.println("private key written to " + file);
      System.out.println("public key (UpdateSignature.RELEASE_PUBLIC_KEY):");
      System.out.println(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
      return;
    }
    if (args.length == 4 && args[0].equals("sign")) {
      byte[] der = Base64.getDecoder().decode(Files.readString(Path.of(args[1])).trim());
      PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
      String version = args[2].trim();
      String sha = args[3].trim().toLowerCase(Locale.ROOT);
      if (!sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 must be 64 hex characters");
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key);
      signer.update(("miaucraft-bridge-update-v1\n" + version + "\n" + sha + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.println(Base64.getEncoder().encodeToString(signer.sign()));
      return;
    }
    System.err.println("usage: java dev/ReleaseSigner.java gen <private-key-file>");
    System.err.println("       java dev/ReleaseSigner.java sign <private-key-file> <version> <sha256>");
    System.exit(2);
  }
}
