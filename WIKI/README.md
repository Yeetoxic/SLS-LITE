# GitHub Wiki Source

This directory is the reviewable source for the SLS-LITE GitHub Wiki. Wiki
pages are concise entry points; detailed behavior remains canonical under
`DOCS/` and in the generated commented configuration files.

## Update Rules

1. Change the canonical repository documentation first.
2. Update only the affected wiki summary or link.
3. Verify that every availability claim describes implemented behavior.
4. Review links and Markdown in the repository pull request or commit.
5. Publish only from the reviewed revision intended for the wiki.

## Publishing

GitHub stores a repository wiki in a separate `SLS-LITE.wiki.git` repository.
Clone that repository into a disposable directory, copy the named wiki pages
from this directory, inspect the resulting diff, and commit/push it separately.
Do not copy this `README.md`, release records, test fixtures, credentials, or
generated data. Do not automatically delete wiki pages that are absent here;
review removals individually.

The publishable source set is:

- `Home.md`
- `_Sidebar.md`
- `_Footer.md`
- `Installation-and-First-Run.md`
- `Configuration.md`
- `Commands-and-Permissions.md`
- `Storage-and-COW.md`
- `Lobby-and-Matchmaking.md`
- `Operations.md`
- `Troubleshooting.md`
- `Compatibility.md`
- `Java-Extension-Development.md`
- `Contributing.md`

After publication, open every sidebar entry from the rendered wiki and confirm
that canonical repository links resolve on the intended branch.
