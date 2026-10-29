-- Media (ADR-011 step H6, ADR-012): the Cloud API media id of a customer's voice note or image, so
-- the reply worker can download it once before answering. The media itself is never stored: audio
-- becomes a transcription in message.text, an image goes to the model only for that answer.
ALTER TABLE message
    ADD COLUMN media_id     text,
    ADD COLUMN media_mime   text,
    ADD COLUMN media_status text,
    ADD CONSTRAINT ck_message_media_status CHECK (media_status IN ('PENDING', 'DONE', 'FAILED'));
CREATE INDEX ix_message_media_pending ON message (conversation_id) WHERE media_status = 'PENDING';
