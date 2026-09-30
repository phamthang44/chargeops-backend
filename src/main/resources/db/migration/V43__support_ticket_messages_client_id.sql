ALTER TABLE ticket_messages
    ADD COLUMN client_message_id uuid;

CREATE UNIQUE INDEX ux_ticket_messages_client_id
    ON ticket_messages(author_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
