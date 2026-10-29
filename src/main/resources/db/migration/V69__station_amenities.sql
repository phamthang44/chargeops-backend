CREATE TABLE station_amenities (
    station_id uuid NOT NULL REFERENCES stations(id),
    amenity_code varchar(20) NOT NULL,
    CONSTRAINT pk_station_amenities PRIMARY KEY (station_id, amenity_code),
    CONSTRAINT ck_station_amenities_code CHECK (amenity_code IN (
        'wifi', 'food', 'coffee', 'parking', 'security',
        'restroom', 'lounge', 'atm', 'carwash', 'shop'
    ))
);
