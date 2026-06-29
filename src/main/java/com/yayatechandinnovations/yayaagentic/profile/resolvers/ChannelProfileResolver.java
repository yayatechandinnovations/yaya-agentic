package com.yayatechandinnovations.yayaagentic.profile.resolvers;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.profile.ProfileResolver;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Reads the channel endpoint hint set by the channel adapter and resolves
 * the bound profile via {@link ChannelBindingService}. Falls through (
 * {@code Optional.empty()}) when no binding matches — by §8.2 design, an
 * empty resolution lands at {@code FallbackProfileResolver} whose default
 * behavior is "no profile" so a WhatsApp inbound never accidentally hits a
 * profile the operator didn't authorize.
 *
 * <p>See {@code whatsapp-channel-design.md} §5.3 + §8.2.
 */
@Component
@Order(30)
public class ChannelProfileResolver implements ProfileResolver {

    private final ChannelBindingService bindings;

    public ChannelProfileResolver(ChannelBindingService bindings) {
        this.bindings = bindings;
    }

    @Override
    public Optional<Ids.ProfileId> resolve(StartConversationRequest req,
                                           AuthContext auth,
                                           Principal principal) {
        Map<String, Object> hints = req.hints();
        if (hints == null || hints.isEmpty()) return Optional.empty();
        String kind = stringOf(hints.get("channel.kind"));
        if (kind == null) return Optional.empty();

        ChannelEndpointRef endpoint = switch (kind) {
            case "whatsapp" -> {
                Object senderId = hints.get("channel.senderId");
                yield senderId == null ? null
                        : new ChannelEndpointRef.WhatsAppSenderRef(new SenderId(senderId.toString()));
            }
            case "chat" -> {
                Object entryPoint = hints.get("channel.entryPoint");
                yield entryPoint == null ? null
                        : new ChannelEndpointRef.ChatEntryPointRef(new ChatEntryPointId(entryPoint.toString()));
            }
            default -> null;
        };
        if (endpoint == null) return Optional.empty();

        return bindings.findFor(req.tenant(), endpoint, principal, hints)
                .map(ChannelBinding::profile);
    }

    private static String stringOf(Object v) {
        return v == null ? null : v.toString();
    }
}
