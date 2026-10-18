package dev.bora.orders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmationCodes {
  private final SecretKeySpec key;

  public OrderConfirmationCodes(@Value("${bora.orders.confirmation-secret}") String secret) {
    if (secret == null || secret.codePointCount(0, secret.length()) < 32) {
      throw new IllegalArgumentException("A chave dos códigos deve ter pelo menos 32 caracteres.");
    }
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public String code(UUID orderId) {
    long number = ByteBuffer.wrap(hmac("order-confirmation-code:v1:" + orderId)).getLong();
    return String.format(Locale.ROOT, "%06d", Math.floorMod(number, 1_000_000L));
  }

  public String hash(UUID orderId) {
    return hash(orderId, code(orderId));
  }

  public boolean verify(UUID orderId, String suppliedCode, String storedHash) {
    if (suppliedCode == null || !suppliedCode.matches("[0-9]{6}") || storedHash == null) {
      return false;
    }
    return MessageDigest.isEqual(
        hash(orderId, suppliedCode).getBytes(StandardCharsets.US_ASCII),
        storedHash.getBytes(StandardCharsets.US_ASCII));
  }

  private String hash(UUID orderId, String code) {
    return HexFormat.of().formatHex(hmac("order-confirmation-verifier:v1:" + orderId + ":" + code));
  }

  private byte[] hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(
          "Não foi possível proteger o código de confirmação.", exception);
    }
  }
}
