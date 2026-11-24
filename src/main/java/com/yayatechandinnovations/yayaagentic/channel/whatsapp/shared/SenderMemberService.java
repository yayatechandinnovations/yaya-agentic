package com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderMemberEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The staff a workspace registered on a platform sender
 * (whatsapp-shared-sender-design.md §7.6). Registering a phone claims only
 * "this person may talk to us here"; the workspace's auth resolver still
 * decides who they are (§7.5).
 */
@Service
public class SenderMemberService {

    public static final int MAX_MEMBERS = 2000;
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final WhatsAppSenderMemberRepository repo;
    private final WhatsAppSenderService senders;

    public SenderMemberService(WhatsAppSenderMemberRepository repo, WhatsAppSenderService senders) {
        this.repo = repo;
        this.senders = senders;
    }

    public List<String> list(Ids.TenantId tenant, SenderId sender) {
        requirePlatform(tenant, sender);
        return repo.findBySenderIdAndTenantIdOrderByPhoneE164Asc(sender.value(), tenant.value()).stream()
                .map(WhatsAppSenderMemberEntity::getPhoneE164)
                .toList();
    }

    public long count(Ids.TenantId tenant, SenderId sender) {
        return repo.countBySenderIdAndTenantId(sender.value(), tenant.value());
    }

    /** Full replace: what is not in {@code phones} is no longer staff here. */
    @Transactional
    public List<String> replaceAll(Ids.TenantId tenant, SenderId sender, List<String> phones) {
        WhatsAppSender s = requirePlatform(tenant, sender);
        Set<String> wanted = new LinkedHashSet<>();
        for (String p : phones == null ? List.<String>of() : phones) wanted.add(validPhone(p, s));
        if (wanted.size() > MAX_MEMBERS) {
            throw AdminApiException.badRequest("too_many_members", "at most " + MAX_MEMBERS + " staff phones");
        }
        repo.deleteBySenderIdAndTenantId(sender.value(), tenant.value());
        repo.flush();
        for (String p : wanted) repo.save(new WhatsAppSenderMemberEntity(sender.value(), tenant.value(), p));
        return list(tenant, sender);
    }

    @Transactional
    public void put(Ids.TenantId tenant, SenderId sender, String phone) {
        WhatsAppSender s = requirePlatform(tenant, sender);
        String p = validPhone(phone, s);
        if (repo.existsById(new WhatsAppSenderMemberEntity.PK(sender.value(), tenant.value(), p))) return;
        if (repo.countBySenderIdAndTenantId(sender.value(), tenant.value()) >= MAX_MEMBERS) {
            throw AdminApiException.badRequest("too_many_members", "at most " + MAX_MEMBERS + " staff phones");
        }
        repo.save(new WhatsAppSenderMemberEntity(sender.value(), tenant.value(), p));
    }

    @Transactional
    public void delete(Ids.TenantId tenant, SenderId sender, String phone) {
        requirePlatform(tenant, sender);
        repo.deleteBySenderIdAndTenantIdAndPhoneE164(sender.value(), tenant.value(), phone == null ? "" : phone.trim());
    }

    /** Ending an assignment forgets the workspace's staff on the number (§11). */
    @Transactional
    public long removeAll(Ids.TenantId tenant, SenderId sender) {
        return repo.deleteBySenderIdAndTenantId(sender.value(), tenant.value());
    }

    private WhatsAppSender requirePlatform(Ids.TenantId tenant, SenderId sender) {
        WhatsAppSender s = senders.requireUsable(tenant, sender);
        if (!s.isPlatform()) {
            throw AdminApiException.badRequest("not_a_platform_sender",
                    "staff are registered only on a shared (platform) number — on the workspace's own "
                            + "number its assistant binding decides who may talk");
        }
        return s;
    }

    private static String validPhone(String phone, WhatsAppSender sender) {
        String p = phone == null ? "" : phone.trim();
        if (!E164.matcher(p).matches()) {
            throw AdminApiException.badRequest("invalid_phone", "phones must be E.164, like +18095551234");
        }
        if (p.equals(sender.phoneE164())) {
            throw AdminApiException.badRequest("invalid_phone", "the shared number itself can't be a member");
        }
        return p;
    }
}
