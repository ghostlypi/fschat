package dev.fschat.daemon.contacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BlockBookTest {

    @Test
    void blockUnblockAndPersist(@TempDir Path tmp) {
        Path file = tmp.resolve("blocks.json");
        BlockBook book = new BlockBook(file);
        assertThat(book.isBlocked("u1")).isFalse();
        book.block("u1");
        book.block("u1"); // idempotent
        assertThat(book.isBlocked("u1")).isTrue();
        assertThat(book.snapshot()).containsExactly("u1");

        assertThat(new BlockBook(file).isBlocked("u1")).isTrue(); // survives reload

        book.unblock("u1");
        assertThat(book.isBlocked("u1")).isFalse();
        assertThat(new BlockBook(file).isBlocked("u1")).isFalse();
    }
}
