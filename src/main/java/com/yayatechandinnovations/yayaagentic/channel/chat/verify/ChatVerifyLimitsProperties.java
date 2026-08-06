package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Env-tunable toll-fraud ceilings for the chat verify + PIN-unlock surface
 * ({@code chat-channel-design.md} §20.4, {@code chat-experience-design.md} §5).
 * Bound under {@code yaya.agentic.chat.verify.limits.*}. Defaults are the
 * shipped production values — deliberately tight, because {@code start} gates
 * real money (Twilio SMS + downstream LLM). Raise them in dev/staging for
 * testing; keep them tight in prod.
 *
 * <p>Every value is per the noted window. {@code globalDailyStarts} is the hard
 * platform-wide backstop; the per-IP and per-phone caps are the ones a single
 * tester trips first (5 SMS sends/day per phone by default).
 */
@ConfigurationProperties("yaya.agentic.chat.verify.limits")
public class ChatVerifyLimitsProperties {

    /** OTP sends per IP per hour. */
    private int startPerIpPerHour = 5;
    /** OTP sends per phone per day — the ceiling a tester hits first. */
    private int startPerPhonePerDay = 5;
    /** Hard platform-wide OTP sends per day. */
    private int globalDailyStarts = 5000;
    /** OTP code checks per phone per hour. */
    private int checkPerPhonePerHour = 10;
    /** Phone-existence probes per IP per hour (no SMS; blunts enumeration). */
    private int lookupPerIpPerHour = 30;
    /** PIN-unlock attempts per IP per hour (no SMS; brute-force surface). */
    private int unlockPerIpPerHour = 60;
    /** PIN-unlock attempts per device per hour. */
    private int unlockPerDevicePerHour = 20;

    public int getStartPerIpPerHour() { return startPerIpPerHour; }
    public void setStartPerIpPerHour(int v) { this.startPerIpPerHour = v; }

    public int getStartPerPhonePerDay() { return startPerPhonePerDay; }
    public void setStartPerPhonePerDay(int v) { this.startPerPhonePerDay = v; }

    public int getGlobalDailyStarts() { return globalDailyStarts; }
    public void setGlobalDailyStarts(int v) { this.globalDailyStarts = v; }

    public int getCheckPerPhonePerHour() { return checkPerPhonePerHour; }
    public void setCheckPerPhonePerHour(int v) { this.checkPerPhonePerHour = v; }

    public int getLookupPerIpPerHour() { return lookupPerIpPerHour; }
    public void setLookupPerIpPerHour(int v) { this.lookupPerIpPerHour = v; }

    public int getUnlockPerIpPerHour() { return unlockPerIpPerHour; }
    public void setUnlockPerIpPerHour(int v) { this.unlockPerIpPerHour = v; }

    public int getUnlockPerDevicePerHour() { return unlockPerDevicePerHour; }
    public void setUnlockPerDevicePerHour(int v) { this.unlockPerDevicePerHour = v; }
}
