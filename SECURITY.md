# Security

## Reporting a vulnerability

Report vulnerabilities privately, through GitHub's private vulnerability
reporting: on [the repository](https://github.com/mpenet/jumble), the
**Security** tab, then **Report a vulnerability**. Please do not open a
public issue, nor discuss it anywhere public, until a fix is out.

Expect a first answer within a week. Once the problem is understood, we
agree on a fix and on when to disclose it, and you are credited unless you
prefer not to be.

## What to include

- what an attacker can do, and who they need to be: anyone, a member, a
  moderator, someone with access to the server;
- the steps to reproduce it, or a proof of concept;
- the version: the commit, or the image digest;
- the configuration involved, if any: how members sign in, HTTPS served by
  Jumble or a proxy of your own;
- how you would fix it, if you have an idea.

Leave out any real member's data.

## What is supported

The latest release (`ghcr.io/mpenet/jumble:latest`) and the code on
`main`. A fix comes in a new release; older releases are not kept up to
date, so upgrading is how a fix reaches a running Jumble (see
[upgrading](doc/operations.md#upgrading)).
