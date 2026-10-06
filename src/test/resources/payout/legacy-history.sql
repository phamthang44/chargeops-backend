-- Historical V45 fixture; committed before upgrading to current Flyway schema.
                INSERT INTO user_profile(keycloak_id,email) VALUES ('payout-schema','payout-schema@example.test');
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,'Payout schema station','Test','000','ACTIVE' FROM user_profile WHERE keycloak_id='payout-schema';
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,'CP-PAYOUT-SCHEMA','ACTIVE','AVAILABLE' FROM stations WHERE name='Payout schema station';
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,'C-PAYOUT-SCHEMA','CCS2',60,'DC','AVAILABLE' FROM charge_points WHERE charge_point_code='CP-PAYOUT-SCHEMA';
                INSERT INTO bookings(driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                    station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,expires_at)
                SELECT u.id,c.id,'BKG-PAYOUT-SCHEMA','2026-09-29 10:00Z','2026-09-29 11:00Z','COMPLETED',
                    100000,'Payout schema station','Test','CP-PAYOUT-SCHEMA','C-PAYOUT-SCHEMA','2026-09-29 09:10Z'
                FROM user_profile u CROSS JOIN connectors c
                WHERE u.keycloak_id='payout-schema' AND c.connector_code='C-PAYOUT-SCHEMA';
                INSERT INTO payments(booking_id,amount,status,method,refund_amount,paid_at,provider,
                    receiving_account_ref,currency,needs_reconciliation,environment,version,payment_code)
                SELECT id,100000,'PAID','SIMULATOR',0,now(),'SIMULATOR','SIMULATOR','VND',false,'SIMULATOR',0,
                    'PAYPAYOUTSCHEMA' FROM bookings WHERE booking_code='BKG-PAYOUT-SCHEMA';
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                    amount,currency,received_at,application_classification,application_reason,payment_code,version)
                SELECT id,'SIMULATOR','SIMULATOR','PAYOUT-APPLIED',100000,'VND',now(),'APPLIED',NULL,payment_code,0
                FROM payments WHERE payment_code='PAYPAYOUTSCHEMA';
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                    amount,currency,received_at,application_classification,application_reason,payment_code,version)
                SELECT id,'SIMULATOR','SIMULATOR','PAYOUT-UNAPPLIED',20000,'VND',now(),'UNAPPLIED','EXTRA',payment_code,0
                FROM payments WHERE payment_code='PAYPAYOUTSCHEMA';

INSERT INTO owner_payouts(payout_code,owner_id,amount,currency,environment,status,period_start,period_end)
SELECT 'PO-20261006-0061',id,100000,'VND','SIMULATOR','PENDING','2026-09-01 00:00Z','2026-10-01 00:00Z'
FROM user_profile WHERE keycloak_id='payout-schema';
INSERT INTO owner_payout_items(payout_id,booking_id,payment_id,amount,currency,environment,status)
SELECT po.id,b.id,p.id,100000,'VND','SIMULATOR','PENDING'
FROM owner_payouts po CROSS JOIN bookings b JOIN payments p ON p.booking_id=b.id
WHERE po.payout_code='PO-20261006-0061' AND b.booking_code='BKG-PAYOUT-SCHEMA';
INSERT INTO payout_attempts(payout_id,sequence_no,execution_mode,request_key,payload_hash,
idempotency_key,amount,currency,environment,status,started_at,performed_by)
SELECT id,1,'SIMULATOR',gen_random_uuid(),repeat('a',64),'legacy-061',100000,'VND','SIMULATOR','STARTED',now(),owner_id
FROM owner_payouts WHERE payout_code='PO-20261006-0061';
INSERT INTO owner_adjustments(owner_id,amount,currency,environment,reason,recorded_at,recorded_by)
SELECT id,-5000,'VND','SIMULATOR','MANUAL_CORRECTION',now(),id FROM user_profile WHERE keycloak_id='payout-schema';
INSERT INTO user_profile(keycloak_id,email) VALUES ('legacy-next-owner','next-owner@example.test');
UPDATE stations SET owner_id=(SELECT id FROM user_profile WHERE keycloak_id='legacy-next-owner')
WHERE name='Payout schema station';
