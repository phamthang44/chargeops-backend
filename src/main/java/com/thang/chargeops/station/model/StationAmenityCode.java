package com.thang.chargeops.station.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Canonical V1 amenities, in the order exposed by Owner and Driver APIs. */
public enum StationAmenityCode {
    WIFI("wifi"),
    FOOD("food"),
    COFFEE("coffee"),
    PARKING("parking"),
    SECURITY("security"),
    RESTROOM("restroom"),
    LOUNGE("lounge"),
    ATM("atm"),
    CARWASH("carwash"),
    SHOP("shop");

    private static final List<StationAmenityCode> CANONICAL_ORDER = List.of(values());

    private final String code;

    StationAmenityCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<StationAmenityCode> find(String code) {
        return Arrays.stream(values())
                .filter(amenity -> amenity.code.equals(code))
                .findFirst();
    }

    public static List<StationAmenityCode> canonicalize(Iterable<String> codes) {
        java.util.EnumSet<StationAmenityCode> selected = java.util.EnumSet.noneOf(StationAmenityCode.class);
        for (String code : codes) {
            StationAmenityCode amenity = find(code)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown station amenity code"));
            selected.add(amenity);
        }
        return CANONICAL_ORDER.stream().filter(selected::contains).toList();
    }
}
