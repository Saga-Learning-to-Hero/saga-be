-- GitHub login of a commit's author, kept apart from author_external_id (the numeric GitHub user id
-- the API sync stores, used to match linked accounts). The login is what people recognise: it is
-- shown for authors who are not members of the team.
ALTER TABLE git_commit ADD COLUMN author_login VARCHAR(128) NULL;
