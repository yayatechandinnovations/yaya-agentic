/**
 * Channel binding — the row that routes a {@code ChannelEndpointRef} to a
 * profile. The thin layer that finally has work to do is
 * {@code ChannelProfileResolver} (§5.6 in the master design): it reads
 * {@code channel.senderId} from {@code StartConversationRequest.hints} and
 * delegates to {@link com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService}.
 *
 * <p>See {@code whatsapp-channel-design.md} §8.
 */
package com.yayatechandinnovations.yayaagentic.channel.binding;
