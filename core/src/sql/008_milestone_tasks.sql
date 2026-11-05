-- Every milestone has a matching task, so it also shows in the Tasks tab. The two
-- stay in step: the milestone is the one that counts as a target, and deleting the
-- milestone deletes its task.
ALTER TABLE tasks ADD COLUMN milestone_id TEXT REFERENCES milestones (id) ON DELETE CASCADE;
CREATE UNIQUE INDEX tasks_by_milestone ON tasks (milestone_id) WHERE milestone_id IS NOT NULL;

-- Tasks for the milestones that already exist, in their goal's order.
INSERT INTO tasks (id, title, created_date, done_date, goal_id, milestone_id)
SELECT lower(hex(randomblob(16))), m.title, g.created_date, m.done_date, g.id, m.id
FROM milestones m JOIN goals g ON g.id = m.goal_id
ORDER BY g.id, m.position;
