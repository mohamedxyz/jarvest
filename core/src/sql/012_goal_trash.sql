-- Deleting a goal moves it to the trash first. The goal keeps its entries, milestones,
-- rewards and reminder, so restoring brings everything back. `deleted_date` is the
-- day it was trashed (YYYY-MM-DD); NULL means the goal is not in the trash.
ALTER TABLE goals ADD COLUMN deleted_date TEXT;
