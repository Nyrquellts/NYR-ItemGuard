package com.nyr.fixes.illegal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The mark on an item staff gave back from quarantine, so the rules do not take it again. The mark is signed with a key only
 * this server holds (review-key in the plugin folder), so a modified client cannot put it on items of its own.
 */
final class ReviewMark {

    private final NamespacedKey key;
    private final byte[] secret;

    private ReviewMark(NamespacedKey key, byte[] secret) {
        this.key = key;
        this.secret = secret;
    }

    static ReviewMark load(NamespacedKey key, File keyFile) throws IOException {
        byte[] secret;
        if (keyFile.isFile()) {
            secret = Base64.getDecoder().decode(Files.readString(keyFile.toPath(), StandardCharsets.US_ASCII).trim());
        } else {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            Files.createDirectories(keyFile.toPath().toAbsolutePath().getParent());
            Files.writeString(keyFile.toPath(), Base64.getEncoder().encodeToString(secret), StandardCharsets.US_ASCII);
        }
        return new ReviewMark(key, secret);
    }

    /** Returns a copy of the item marked as reviewed under the quarantine id. */
    ItemStack mark(ItemStack item, String id) {
        ItemStack marked = item.clone();
        ItemMeta meta = marked.getItemMeta();
        if (meta == null) {
            return marked;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id + ":" + signature(id, marked.getType()));
        marked.setItemMeta(meta);
        return marked;
    }

    boolean marked(ItemMeta meta, Material type) {
        String value = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null) {
            return false;
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0) {
            return false;
        }
        byte[] expected = signature(value.substring(0, colon), type).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, value.substring(colon + 1).getBytes(StandardCharsets.US_ASCII));
    }

    NamespacedKey key() {
        return key;
    }

    private String signature(String id, Material type) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((id + ":" + type.name().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException missingHmac) {
            throw new IllegalStateException("HmacSHA256 is not available", missingHmac);
        }
    }
}
