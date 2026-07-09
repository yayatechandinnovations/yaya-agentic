package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliverySlots;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Decision 10 — the slot is guarded exactly like a stock summary row (§5.2):
 * bookers serialize on the pessimistically locked slot, so the last unit of
 * capacity is taken exactly once (§14 no slot oversell). The bookings table
 * makes both book and release idempotent per order.
 */
@Service
public class JpaDeliverySlots implements DeliverySlots {

    private final DeliverySlotRepository slots;
    private final SlotBookingRepository bookings;

    public JpaDeliverySlots(DeliverySlotRepository slots, SlotBookingRepository bookings) {
        this.slots = slots;
        this.bookings = bookings;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SlotView> available(StoreId store, DeliveryMethodId method, LocationId location,
                                    Instant from, Instant to) {
        return slots
                .findByStoreIdAndMethodIdAndLocationIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqualOrderByStartsAtAsc(
                        store.value(), method.value(), location.value(), from, to)
                .stream()
                .map(JpaDeliverySlots::toView)
                .filter(s -> !s.full())
                .toList();
    }

    @Override
    @Transactional
    public void book(SlotId slot, OrderId order) {
        DeliverySlotEntity locked = locked(slot);
        SlotBookingEntity.Key key = new SlotBookingEntity.Key(locked.getId(), order.value());
        if (bookings.existsById(key)) {
            return;   // a retried checkout re-attaches to its booking
        }
        locked.take();
        bookings.save(new SlotBookingEntity(key, Instant.now()));
    }

    @Override
    @Transactional
    public void release(SlotId slot, OrderId order) {
        DeliverySlotEntity locked = locked(slot);
        SlotBookingEntity.Key key = new SlotBookingEntity.Key(locked.getId(), order.value());
        if (!bookings.existsById(key)) {
            return;   // never booked, or already released — nothing to give back
        }
        bookings.deleteById(key);
        locked.giveBack();
    }

    private DeliverySlotEntity locked(SlotId slot) {
        return slots.lockById(slot.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("delivery slot", slot.value()));
    }

    private static SlotView toView(DeliverySlotEntity slot) {
        return new SlotView(new SlotId(slot.getId()), new StoreId(slot.getStoreId()),
                new LocationId(slot.getLocationId()), new DeliveryMethodId(slot.getMethodId()),
                slot.getStartsAt(), slot.getEndsAt(), slot.getCapacity(), slot.getBooked());
    }
}
