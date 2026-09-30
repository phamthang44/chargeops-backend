-- BKG-050: human-readable ticket codes; gaps are acceptable after rollbacks.
CREATE SEQUENCE support_ticket_code_seq AS bigint START WITH 1 INCREMENT BY 1;
