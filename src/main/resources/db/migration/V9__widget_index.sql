-- Widgets are always read per user and in order
CREATE INDEX widgets_user_idx ON widgets (user_id, position);
