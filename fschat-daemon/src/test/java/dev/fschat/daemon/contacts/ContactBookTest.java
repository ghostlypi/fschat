package dev.fschat.daemon.contacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContactBookTest {

    @Test
    void labelPrefersNicknameOverUsername() {
        assertThat(new Contact("u1", "john", "JDog").label()).isEqualTo("JDog");
        assertThat(new Contact("u1", "john", null).label()).isEqualTo("john");
        assertThat(new Contact("u1", "john", "  ").label()).isEqualTo("john");
    }

    @Test
    void upsertNickAndClearPersistAcrossReload(@TempDir Path tmp) {
        Path file = tmp.resolve("contacts.json");
        ContactBook book = new ContactBook(file);
        book.upsert("u1", "john", null);
        book.setNickname("u2", "john", "JDog");          // a second john, nicknamed
        assertThat(book.get("u1")).map(Contact::label).contains("john");
        assertThat(book.get("u2")).map(Contact::label).contains("JDog");

        ContactBook reloaded = new ContactBook(file);
        assertThat(reloaded.all()).hasSize(2);
        assertThat(reloaded.get("u2")).map(Contact::nickname).contains("JDog");

        reloaded.clearNickname("u2");
        assertThat(reloaded.get("u2")).map(Contact::label).contains("john");
    }

    @Test
    void upsertPreservesExistingNicknameWhenNoneGiven() {
        ContactBook book = new ContactBook(Path.of(System.getProperty("java.io.tmpdir"),
                "fschat-cbtest-" + System.nanoTime() + ".json"));
        book.setNickname("u1", "john", "JDog");
        book.upsert("u1", "john-renamed", null); // username update, nickname kept
        assertThat(book.get("u1")).map(Contact::nickname).contains("JDog");
        assertThat(book.get("u1")).map(Contact::username).contains("john-renamed");
    }
}
