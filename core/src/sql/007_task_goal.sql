-- A task can belong to a goal. The link only groups tasks and shows progress:
-- finishing a task never logs the goal and never earns a reward.
-- Deleting the goal leaves its tasks in place, unlinked.
ALTER TABLE tasks ADD COLUMN goal_id TEXT REFERENCES goals (id) ON DELETE SET NULL;
CREATE INDEX tasks_by_goal ON tasks (goal_id);
