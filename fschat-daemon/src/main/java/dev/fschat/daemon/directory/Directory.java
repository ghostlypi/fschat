package dev.fschat.daemon.directory;

import dev.fschat.daemon.contacts.BlockBook;
import dev.fschat.daemon.contacts.Contact;
import dev.fschat.daemon.contacts.ContactBook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Daemon-wide resolver for how users are displayed and addressed locally. Wraps
 * the {@link ContactBook} + {@link BlockBook} and owns the (daemon-wide) label
 * collision map. It is the single seam the {@link dev.fschat.daemon.sync.ChannelView}
 * uses instead of hand-building {@code name:hexid}.
 *
 * <p>Display rule: a contact renders as its label, or {@code label:hexid} if that
 * label collides with another contact's label; a non-contact always renders the
 * full {@code username:hexid} handle.
 */
public final class Directory {

    private final ContactBook contacts;
    private final BlockBook blocks;
    private volatile String meHandle;
    private volatile Map<String, Integer> labelCounts = Map.of();

    public Directory(ContactBook contacts, BlockBook blocks) {
        this.contacts = contacts;
        this.blocks = blocks;
        recompute();
    }

    public void setMeHandle(String meHandle) {
        this.meHandle = meHandle;
    }

    public String meHandle() {
        return meHandle;
    }

    public ContactBook contacts() {
        return contacts;
    }

    public BlockBook blocks() {
        return blocks;
    }

    /** Recompute the label-collision map; call after any contact mutation. */
    public synchronized void recompute() {
        Map<String, Integer> counts = new HashMap<>();
        for (Contact c : contacts.all()) {
            counts.merge(c.label(), 1, Integer::sum);
        }
        this.labelCounts = Map.copyOf(counts);
    }

    /** How to render a message author (the {@code #msg author=} value). */
    public String displayAuthor(String authorId, String username) {
        Optional<Contact> c = contacts.get(authorId);
        if (c.isPresent()) {
            String label = c.get().label();
            return labelCounts.getOrDefault(label, 0) > 1 ? label + ":" + authorId : label;
        }
        return (username != null && !username.isEmpty() ? username : "unknown") + ":" + authorId;
    }

    public boolean isBlocked(String authorId) {
        return blocks.isBlocked(authorId);
    }

    /** Contacts whose effective label equals {@code label} (1 = unambiguous, &gt;1 = ambiguous). */
    public List<Contact> contactsWithLabel(String label) {
        List<Contact> matches = new ArrayList<>();
        for (Contact c : contacts.all()) {
            if (c.label().equals(label)) {
                matches.add(c);
            }
        }
        return matches;
    }
}
