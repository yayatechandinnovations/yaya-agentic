package com.yayatechandinnovations.yayasales.cart.internal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<CartEntity, String> {
}
