# DiffPlug Webtools

- [node](#node) - hassle-free `npm install` and `npm run blah`
- [static server](#static-server) - a simple static file server

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
