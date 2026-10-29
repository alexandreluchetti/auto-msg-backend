-- Natural handoff (ADR-011 step H7, D-015): staff can take a conversation over from the panel.
ALTER TABLE conversation DROP CONSTRAINT ck_conversation_handoff_reason;
ALTER TABLE conversation ADD CONSTRAINT ck_conversation_handoff_reason
    CHECK (handoff_reason IN ('STAFF_REPLY', 'STAFF_TAKEOVER', 'CUSTOMER_REQUEST', 'BOT_REQUEST', 'REPEATED_FAILURES'));
