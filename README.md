# Daiku

A local, single-user project workspace built with Java 21, Spring Boot, Spring MVC/Thymeleaf, JDBC, and Flyway. Increment one provides projects, a five-column task board, manual tickets, ticket edit history, and GitHub Actions build visibility.

## Try it

Install JDK 21 and Maven 3.9+ (the implementation was also exercised with this machine's JDK 27). From this directory:

```sh
mvn package
java -jar app/target/daiku-app-0.1.0-SNAPSHOT.jar
```

Open <http://localhost:8080>. Create a local project and add a ticket. Drag cards between board columns to save status changes, or use each card's **Move to…** control with a keyboard or touch screen. Open a card to edit its details and priority. Moves preserve ticket details and record activity history; if another tab changed the ticket, reload the board before retrying. Data persists in `data/daiku.mv.db`; no GitHub credentials are needed for this path. To back up the local database, stop the application before copying `data/`.

The default bind address is `127.0.0.1`. This increment has no user authentication and is intended for your own computer. Add authentication before making the UI remotely accessible. Forms use session-bound CSRF tokens and templates escape ticket content. Connecting repositories grants read access only; this increment does not push code, execute agents, trigger builds, merge, or deploy. Builds shown here are existing GitHub Actions workflow runs.

To remove a ticket, open it and choose **Delete ticket**. Review the confirmation page, then choose **Delete ticket permanently**, or **Cancel** to keep it. Deletion also removes its activity history and returns you to the project board.

### Archive projects

Choose **Archive project** on a project board to set it aside. Archived projects disappear from the active navigation and appear under **Archived projects**, where you can open or restore them. Their tickets, activity history, imported builds, and GitHub connection are preserved. Ticket changes and build refreshes are disabled while archived; polling skips them and webhook updates are ignored. Restore a project to resume work and polling. Connecting the same repository again opens its archived project without restoring it automatically.

Archiving does not delete data or change the GitHub repository or App installation.

### This workspace's offline environment

Maven Central cannot be resolved here, and the shared Maven cache is read-only. Build and test offline with:

```sh
mvn -o -Daether.syncContext.named.factory=noop test
mvn -o -Daether.syncContext.named.factory=noop package
java -jar app/target/daiku-app-0.1.0-SNAPSHOT.jar
```

The `noop` lock option is only for using this read-only cache. Spring Boot 3.5.3 was selected because its dependencies are cached. Refresh the Spring Boot patch version and review dependency updates when network access is available, before deployment.

## Connect GitHub

1. Create a GitHub App in GitHub's developer settings. For this increment, disable user authorization during installation; no OAuth callback is needed.
2. Set repository permissions: **Metadata: read** (implicit), **Contents: read**, **Actions: read**, and **Pull requests: read** (for linked PR status). No write permissions are needed.
3. Install the App on the repositories you want to connect. Download its RSA private key and store it outside the repository. Both GitHub's PKCS#1 PEM and PKCS#8 PEM formats are supported.
4. Export the following variables and restart Daiku. The application does not automatically load `.env` files.

```sh
export GITHUB_APP_ID='your-app-id'
export GITHUB_PRIVATE_KEY_PATH='/absolute/path/to/private-key.pem'
# Optional, for receiving webhooks:
export GITHUB_WEBHOOK_SECRET='your-random-webhook-secret'
mvn package
java -jar app/target/daiku-app-0.1.0-SNAPSHOT.jar
```

5. Choose **Add a project**, enter `owner/repository` (or its `https://github.com/…` URL), and select **Connect repository**. Daiku discovers the installation, reads repository metadata, and imports the most recent 30 workflow runs. An existing connection is reused when you submit the same repository again. To connect an existing local project instead, open its board and choose **Connect GitHub**. Its name, tickets, activity history, and board URL are preserved. Repositories already connected to another project (including an archived one) cannot be attached. Restore archived local projects before connecting; existing repository connections cannot be replaced. A failed initial build refresh keeps the connection so you can retry from the board.

If a connection fails, Daiku identifies the failed GitHub step and HTTP status. An installation lookup failure (404) can mean a misspelled repository or that the App is not installed with access to it. Check the installation on the account that owns the repository and include the private repository under **Repository access**. Changes to App permissions must also be approved for the installation. Authentication errors (401) point to the App ID/key pair or system clock; Actions access errors should prompt a check of the installation's **Actions: read** permission. Error messages omit credentials and upstream response bodies.

Builds can be refreshed manually and are polled every five minutes. The UI shows the latest 15 imported runs, with links to GitHub for full logs and artifacts. Workflows that have not run yet do not appear. Older runs beyond the 30-run refresh window are not backfilled.

### Optional live webhooks

Polling works without a webhook. To receive live updates, configure the GitHub App webhook URL as `https://your-webhook-host/webhooks/github`, with the same secret as `GITHUB_WEBHOOK_SECRET`, and subscribe to **Workflow run** events. Configure your HTTPS tunnel or proxy to forward **only `/webhooks/github`** to localhost:8080; keep the unauthenticated UI private. GitHub can then reach the webhook without exposing the rest of the application.

Payload signatures are checked against the exact request bytes. Valid workflow deliveries are persisted before acknowledging with HTTP 202. Delivery IDs are deduplicated. A background job processes them every five seconds with up to five retries; successful delivery bodies are cleared, and build updates ignore stale timestamps and older rerun attempts. Events for unconnected installations are ignored. Polling reconciles recent build state when a webhook was missed. Inspect failed deliveries with `SELECT delivery_id, attempts FROM webhook_deliveries WHERE state='FAILED'`; after fixing the cause, a delivery can be requeued by setting its state to `PENDING`, attempts to `0`, and next_attempt_at to `CURRENT_TIMESTAMP`.

## Ticket development links

On a connected project's ticket page, use **Development** to link one branch and/or one pull request. Enter a branch name such as `feature/ticket-123` and a PR number (`123` or `#123`) or a `https://github.com/owner/repository/pull/123` URL from the project's repository. Save the links; leave a field empty to remove it. Links use the ticket's version checks and appear in activity history. Ticket edits and board moves retain them. Local projects need a GitHub connection first; archived projects retain read-only links and cached status.

The ticket shows the PR title, Draft/Open/Closed/Merged state, and related GitHub Actions runs with their commit SHAs. Saving links refreshes status; **Refresh development status** retries on demand. Linked PRs are polled every five minutes alongside existing build polling. Viewing tickets uses local cached data and does not contact GitHub. Give the GitHub App **Pull requests: read** permission and approve that permission update on its installation. Failures retain saved links and any last known PR state, with a visible error and refresh timestamp. Board moves remain manual; merging a PR does not move tickets automatically.

For a linked PR, related builds match its last refreshed head SHA or merge commit SHA, scoped to the project's repository. A new head commit has no matching builds until its runs are imported; older green runs are not substituted. Without a PR, builds match the linked branch and source repository, excluding same-named fork branches. Existing runs without source-repository metadata become eligible after a GitHub refresh supplies it. Ticket refreshes fetch up to 30 runs for the branch or each PR commit, so busy repositories do not hide ticket builds behind unrelated recent runs. Up to 15 matching imported runs are displayed; missing runs and external check providers are not inferred as success. PR webhooks are not required: PR state uses polling and explicit refresh, while workflow webhooks continue to update builds.

## PostgreSQL

The default file-based H2 database makes the application immediately usable. For PostgreSQL, with network access to download the JDBC driver and Flyway database module:

```sh
docker compose up -d postgres
mvn -Ppostgres package
java -jar app/target/daiku-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres
```

Configure `DATABASE_URL` (a JDBC URL), `DATABASE_USER`, and `DATABASE_PASSWORD` to use another PostgreSQL instance. The compose defaults are local development credentials. Flyway applies the same migration to both databases. Switching profiles creates a separate database; it does not move local H2 data into PostgreSQL. PostgreSQL execution has not been verified in this restricted environment.

## Development

```sh
mvn spotless:apply # Format Java sources and tests
mvn spotless:check # Check formatting without changing files
mvn test
mvn verify         # Run tests, build the JAR, and check formatting
java -jar app/target/daiku-app-0.1.0-SNAPSHOT.jar
```

Java formatting uses Spotless with Google Java Format's `GOOGLE` style (two-space indentation), import sorting, and unused import removal. Both tool versions are pinned in `pom.xml`; main sources and tests use the same rules. Configure your IDE to use Google Java Format, or run `mvn spotless:apply` before committing. The existing GitHub Actions `mvn verify` step also checks formatting and fails if changes are required; it does not rewrite files.

Some Spotless dependencies are missing from this workspace's offline Maven cache. Its Maven commands require an initial dependency download when network access is available. The pinned Google Java Format 1.33.0 was run directly under a locally available JDK 25 to format and check all Java files here. Run Maven with the project's recommended JDK 21 (as CI does); this formatter version is incompatible with the machine's default JDK 27 compiler internals.

The integration suite exercises forms and templates, CSRF, escaping, validation, optimistic edit conflicts, signed webhooks, delivery deduplication, installation boundaries, and out-of-order build events. GitHub App authentication is tested with Spring's in-process HTTP test support and generated RSA keys; tests do not use GitHub credentials or contact GitHub. GitHub Actions runs the Maven verification workflow on pushes and pull requests. This sandbox blocks socket binding, so a running server and browser preview could not be verified here; the UI routes are rendered through MockMvc.

The root POM builds four Maven modules in one reactor:

| Module | Responsibility | Internal dependencies |
| --- | --- | --- |
| `core` | Plain Java domain objects under `dev.daiku.domain` (`project`, `ticket`, `build`), shared project repository contract, application errors | None |
| `board` | Ticket validation, status changes, optimistic versioning and activity history; ticket repository contract and JDBC adapter | `core` |
| `cicd` | Build projections, GitHub App client, repository discovery, polling and signed webhook inbox | `core` |
| `app` | Spring Boot entry point, MVC controllers, templates, project JDBC adapter, configuration and Flyway migrations | `core`, `board`, `cicd` |

`board` and `cicd` do not depend on each other. `app` composes both features into one process, and only its JAR is repackaged as an executable. Build and test from the repository root so Maven resolves sibling modules in the reactor. For a feature and its prerequisites, use `mvn -pl board -am test` or `mvn -pl cicd -am test`. The full MVC/database integration suite lives in `app`; GitHub client tests live in `cicd`.

The architecture uses a few boundaries rather than a full set of layers for every class. Core has no Spring, JDBC, Jackson or HTTP dependencies. Ticket workflows depend on a `TicketRepository` interface, with transactions spanning ticket changes and history writes. Shared project access goes through `ProjectRepository`. Application failures are mapped to HTTP responses by the web adapter. Spring annotations remain in the feature modules; GitHub JSON handling and build persistence remain together in CI/CD for now.

Keep the core module small: shared domain types and contracts belong there, while feature behavior belongs in its owning module. Keep the existing Flyway migration and database together to preserve existing local data. If another CI provider is added, introduce a provider interface and typed workflow input at that point. Likewise, split repository connection metadata from `Project` only when its lifecycle needs to evolve independently. Separate services, generic repositories, command buses and duplicated DTOs would add little value at the current size.

Run one application instance in this increment. The webhook processor and scheduler assume a single coordinator; distributed job claims and leases belong in the execution increment. GitHub private keys remain on the server and are never rendered or stored in the database. Installation tokens are generated on demand and retained only during the request.

Moving a ticket to **Ready** currently records the change in its activity history. Agent execution, versioned approval policies, pull requests, merge/deployment workflows, and multi-user authentication are future increments.
