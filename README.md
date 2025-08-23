# DiffPlug Webtools

- [node](#node) - hassle-free `npm install` and `npm run blah`
- [static server](#static-server) - a simple static file server
- [jte](#jte) - creates idiomatic Kotlin model classes for `jte` templates (strict nullability & idiomatic collections and generics)
- [flywayjooq](#flywayjooq) - coordinates docker, flyway, and jOOQ for fast testing

## Node

```gradle
apply plugin: 'com.diffplug.webtools.node'
node {
  // looks for an `.nvmrc` in this folder or its parent
  // downloads the corresponding version of node `npm ci`

  // and then it will run `npm run blah` like so
  npm_run 'blah', {
    inputs.file('tsconfig.json').withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir('somedir').withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir('build/some-output')
  }
  // if an npm script contains `:` it willbe transformed into `-`
  npm_run 'lint:fix', {} // becomes `npm_run_lint-fix`
}
```

## Static Server

```gradle
tasks.register('serve', com.diffplug.webtools.serve.StaticServerTask) {
  dir = file('build/static')
  port = 8080 // by default
}
```

### JTE

You have to apply `gg.jte.gradle` plugin yourself. We add a task called `jteModels` which creates a Kotlin model classes with strict nullability. Like so:

```jte
// header.jte
@param String title
@param String createdAtAndBy
@param Long idToImpersonateNullable
@param String loginLinkNullable
```

will turn into

```kotlin
class header(
  val title: String,
  val createdAtAndBy: String,
  val idToImpersonateNullable: Long?,
  val loginLinkNullable: String?,
  ) : common.JteModel {

  override fun render(engine: TemplateEngine, output: TemplateOutput) {
    engine.render("pages/Admin/userShow/header.jte", mapOf(
      "title" to title,
      "createdAtAndBy" to createdAtAndBy,
      "idToImpersonateNullable" to idToImpersonateNullable,
      "loginLinkNullable" to loginLinkNullable,
    ), output)
  }
}
```

We also translate Java collections and generics to their Kotlin equivalents. See `JteRenderer.convertJavaToKotlin` for details.

### flywayjooq

Compile tasks just need to depend on the `jooq` task. It will keep a live database running to test against.

```gradle
flywayJooq {
  // starts this docker container which needs to have postgres
  setup.dockerComposeFile = file('src/test/resources/docker-compose.yml')
  // writes out connection data to this file
  setup.dockerConnectionParams = file('build/pgConnection.properties')
  // migrates a template database to this
  setup.flywayMigrations = file('src/main/resources/db/migration')
  // dumps the final schema out to this
  setup.flywaySchemaDump = file('src/test/resources/schema.sql')
  // sets up jOOQ
  configuration {
    // jOOQ setup same as the official jOOQ plugin
  }
}
```
