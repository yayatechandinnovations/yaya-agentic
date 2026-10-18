package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ConversationHoldEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ConversationHoldRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Human hold, the operator-takeover switch of {@code whatsapp-channel-design.md} §11.3. An integration that answers a
 * customer itself — a support desk picking up "talk to a person" — holds the conversation while a person is on it:
 * inbound is still acked and stored, but the engine never runs, so the customer does not get a bot reply on top of
 * the person's. Outbound is untouched, which is what lets the person answer through the system-messaging edge.
 *
 * <p>Keyed by (tenant, end-user phone) rather than by sender: a phone belongs to one person whatever sender they
 * reached, and the integration that sets the hold knows the phone, not our sender ids. Every hold carries an expiry,
 * so a desk that forgets to release one goes back to the assistant instead of muting the customer forever.</p>
 */
@Service
public class ConversationHoldService {

    /** Longest a hold may run before the assistant takes the conversation back. */
    public static final Duration MAX_HOLD = Duration.ofDays(7);

    private final ConversationHoldRepository repo;

    public ConversationHoldService(ConversationHoldRepository repo) {
        this.repo = repo;
    }

    /** True while a person is handling this conversation; an expired hold is not a hold. */
    public boolean isHeld(Ids.TenantId tenant, String endUserPhone) {
        return find(tenant, endUserPhone).isPresent();
    }

    /** The live hold, if any. */
    public Optional<ConversationHoldEntity> find(Ids.TenantId tenant, String endUserPhone) {
        return repo.findByTenantIdAndEndUserPhone(tenant.value(), endUserPhone)
                .filter(h -> h.getHeldUntil().isAfter(now()));
    }

    /** Holds (or extends) the conversation. A duration past {@link #MAX_HOLD} is clamped. */
    @Transactional
    public ConversationHoldEntity hold(Ids.TenantId tenant, String endUserPhone, Duration duration, String reason) {
        Duration d = duration == null || duration.isNegative() || duration.isZero() ? Duration.ofHours(24) : duration;
        if (d.compareTo(MAX_HOLD) > 0) {
            d = MAX_HOLD;
        }
        OffsetDateTime until = now().plus(d);
        return repo.findByTenantIdAndEndUserPhone(tenant.value(), endUserPhone)
                .map(existing -> {
                    existing.setHeldUntil(until);
                    existing.setReason(reason);
                    return repo.save(existing);
                })
                .orElseGet(() -> repo.save(new ConversationHoldEntity(tenant.value(), endUserPhone, until, reason)));
    }

    /** Gives the conversation back to the assistant. Releasing a conversation that was never held is a no-op. */
    @Transactional
    public void release(Ids.TenantId tenant, String endUserPhone) {
        repo.findByTenantIdAndEndUserPhone(tenant.value(), endUserPhone).ifPresent(repo::delete);
    }

    /** What a person is handling right now in this tenant. */
    public List<ConversationHoldEntity> active(Ids.TenantId tenant) {
        return repo.findByTenantIdAndHeldUntilAfter(tenant.value(), now());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
