# DiffPlug Webtools

- [node](#node) - hassle-free `npm install` and `npm run blah`
- [static server](#static-server) - a simple static file server
- [jte](#jte) - creates idiomatic Kotlin model classes for `jte` templates (strict nullability & idiomatic collections and generics)

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
