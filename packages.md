# Module vier

The VIER module is split up in 3 main categories:
- Content Fetching
- EPG information
- Authentication/Profile

A common model `ApiResponse` model is used for **all** packages.

# Package be.tapped.vier.content

Since VIER is missing an official API the content is scraped from the public HTML page.  
Therefore it is advised to cache calls to these services as much as possible.

Your entry point is the `VierApi` class

# Package be.tapped.vier.epg

Fetch program guide information by date.

Your entry point is the `HttpEpgRepo`

# Package be.tapped.vrtnu.profile

With this package you can log in to the VIER authentication.
VIER's authentication API is backed by AWS Incognito.

Your entry point is the `HttpProfileRepo`
