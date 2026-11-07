# Repository Guidelines

## Project Structure & Module Organization

Daiku is a Java 21, Spring Boot workspace built as four Maven modules:

- `core/`: framework-free domain types, shared repository contracts, and application errors.
- `board/`: ticket workflows, validation, history, and JDBC persistence.
- `cicd/`: GitHub App authentication, repository discovery, build polling, and webhooks.
- `app/`: executable application, MVC controllers, configuration, and project persistence.

Java sources use `src/main/java`; tests use `src/test/java`. Templates, CSS, and Flyway migrations live under `app/src/main/resources/{templates,static/css,db/migration}`. Keep `core` free of Spring/JDBC dependencies; `board` and `cicd` must remain independent. Build from the repository root to resolve sibling modules.

## Build, Test, and Development Commands

Use JDK 21 and Maven 3.9+:

- `mvn package`: run tests and build the executable JAR.
- `java -jar app/target/daiku-app-0.1.0-SNAPSHOT.jar`: start locally at `http://localhost:8080`.
- `mvn test`: run all module tests.
- `mvn -pl cicd -am test`: test CI/CD and its prerequisites.
- `mvn spotless:apply`: format Java sources and tests.
- `mvn verify`: test, package, and check formatting; also runs in GitHub Actions.

For this workspace’s read-only Maven cache, use `mvn -o -Daether.syncContext.named.factory=noop test`. Spotless needs dependencies absent from the offline cache; see `README.md` for limitations.

## Coding Style & Naming Conventions

Use Spotless’s pinned Google Java Format configuration: two-space indentation and formatter-managed imports. Use `UpperCamelCase` class names, `lowerCamelCase` methods/fields, and lowercase packages under `dev.daiku`. Place feature behavior in its owning module and preserve transaction boundaries around ticket changes and history writes.

## Testing Guidelines

Tests use JUnit Jupiter, AssertJ, and Spring test support. Name classes `*Test` and methods descriptively, such as `rejectsMissingCsrfAndCrossSessionCsrf`. MVC/database tests belong in `app`; GitHub client tests belong in `cicd`. Use MockMvc, in-memory H2, and MockRestServiceServer; tests should require neither real credentials nor GitHub access. Cover changed behavior and relevant validation/security regressions. No numeric coverage threshold is configured.

## Commit & Pull Request Guidelines

Git history is unavailable in this checkout, so no existing commit convention could be verified. Use concise, imperative commit subjects. PRs should describe the problem, resulting behavior, and validation; link relevant issues and include screenshots for UI changes. Run `mvn verify` before submitting when dependencies are available.

## Security & Configuration

Keep private keys and credentials outside version control. Export GitHub settings explicitly; `.env` files are not automatically loaded. Keep the unauthenticated UI bound to localhost. Preserve existing local data when changing Flyway migrations, and stop the application before backing up `data/`.
