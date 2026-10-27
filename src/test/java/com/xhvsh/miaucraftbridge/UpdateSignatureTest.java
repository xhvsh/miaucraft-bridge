package com.xhvsh.miaucraftbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signature is the only thing standing between a compromised GitHub account
 * and code execution on the server, so every way of getting it wrong has a test.
 */
class UpdateSignatureTest {

  private static final String VERSION = "2.7.0";
  private static final String SHA = "ab".repeat(32);

  private static KeyPair newPair() throws Exception {
    return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private static String sign(KeyPair pair, String version, String sha) throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(pair.getPrivate());
    signer.update(UpdateSignature.message(version, sha));
    return Base64.getEncoder().encodeToString(signer.sign());
  }

  @Test
  void validSignatureVerifies() throws Exception {
    KeyPair pair = newPair();
    assertTrue(UpdateSignature.verify(pair.getPublic(), VERSION, SHA, sign(pair, VERSION, SHA)));
  }

  @Test
  void digestIsCaseInsensitiveButOtherwiseExact() throws Exception {
    KeyPair pair = newPair();
    String sig = sign(pair, VERSION, SHA);
    assertTrue(UpdateSignature.verify(pair.getPublic(), VERSION, SHA.toUpperCase(), sig));
  }

  @Test
  void aSignatureCannotBeReusedForAnotherJar() throws Exception {
    KeyPair pair = newPair();
    String sig = sign(pair, VERSION, SHA);
    assertFalse(UpdateSignature.verify(pair.getPublic(), VERSION, "cd".repeat(32), sig));
  }

  @Test
  void aSignatureCannotBeReusedForAnotherVersion() throws Exception {
    KeyPair pair = newPair();
    String sig = sign(pair, VERSION, SHA);
    assertFalse(UpdateSignature.verify(pair.getPublic(), "2.7.1", SHA, sig));
  }

  @Test
  void aDifferentKeyIsRejected() throws Exception {
    String sig = sign(newPair(), VERSION, SHA);
    assertFalse(UpdateSignature.verify(newPair().getPublic(), VERSION, SHA, sig));
  }

  @Test
  void missingOrMalformedSignaturesAreRejectedWithoutThrowing() throws Exception {
    KeyPair pair = newPair();
    assertFalse(UpdateSignature.verify(pair.getPublic(), VERSION, SHA, null));
    assertFalse(UpdateSignature.verify(pair.getPublic(), VERSION, SHA, ""));
    assertFalse(UpdateSignature.verify(pair.getPublic(), VERSION, SHA, "not base64 !!"));
    assertFalse(UpdateSignature.verify(pair.getPublic(), VERSION, SHA, Base64.getEncoder().encodeToString(new byte[64])));
    assertFalse(UpdateSignature.verify(null, VERSION, SHA, sign(pair, VERSION, SHA)));
  }

  @Test
  void tamperedSignatureBytesAreRejected() throws Exception {
    KeyPair pair = newPair();
    byte[] sig = Base64.getDecoder().decode(sign(pair, VERSION, SHA));
    sig[0] ^= 0x01;
    assertFalse(UpdateSignature.verify(pair.getPublic(), VERSION, SHA, Base64.getEncoder().encodeToString(sig)));
  }

  @Test
  void builtInReleaseKeyIsAValidEd25519Key() {
    assertDoesNotThrow(() -> UpdateSignature.parsePublicKey(UpdateSignature.RELEASE_PUBLIC_KEY));
  }

  /** dev/ReleaseSigner.java hard-codes the same layout; keep the two in lockstep. */
  @Test
  void signedMessageLayoutMatchesTheReleaseTooling() {
    byte[] expected = ("miaucraft-bridge-update-v1\n" + VERSION + "\n" + SHA + "\n").getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(expected, UpdateSignature.message(VERSION, SHA));
  }
}
