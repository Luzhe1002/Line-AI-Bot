-- Force a brand-new rich menu ID so LINE clients cannot reuse the image or
-- cached menu object from the first zh-TW rollout.
update merchant_rich_menus
set line_rich_menu_id = null,
    status = 'PENDING',
    last_error = null,
    updated_at = current_timestamp;

update merchant_rich_menu_sync
set revision = revision + 1,
    status = 'READY',
    attempts = 0,
    next_attempt_at = current_timestamp,
    locked_at = null,
    last_error = null,
    updated_at = current_timestamp
where desired_linked = true;
