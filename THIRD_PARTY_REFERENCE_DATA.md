# Third-Party Reference Data

## OpenStreetMap-derived reference preview

The downloadable `world-experiment8-binary-v4` reference package contains
information derived from OpenStreetMap. It is a geographically global preview
of place labels and named waterway lines; it is not a complete all-feature
world map or a completeness claim for islands, coastlines, borders, oceans,
lakes, or every OpenStreetMap feature class.

**Attribution:** © OpenStreetMap contributors

- OpenStreetMap copyright and attribution: https://www.openstreetmap.org/copyright
- Database license: Open Data Commons Open Database License 1.0 (ODbL 1.0)
- License URI: https://opendatacommons.org/licenses/odbl/1-0/
- Included license text: [LICENSES/ODbL-1.0.txt](LICENSES/ODbL-1.0.txt)

The source snapshot is the OpenStreetMap planet file dated 2026-06-29:

- URL: `https://planet.openstreetmap.org/pbf/planet-260629.osm.pbf`
- Bytes: `93,653,630,756`
- SHA-256: `cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f`

Flight Alert deterministically selects source-backed place labels and named
`river`, `stream`, `canal`, `tidal_channel`, and `wadi` line features, then
encodes them into the app's six-file reference package. The transformation
code is under `tools/experiment8/`, and exact source/build receipts remain
embedded in the package receipts.

### ODbL source offer

For a published reference preview, the complete machine-readable derived
database is the six-file package reconstructed from the GitHub release assets.
It is offered free of charge under ODbL 1.0. The v2 release manifest binds every
file and shard by exact byte length and SHA-256; the repository downloader
reconstructs those bytes without pruning or conversion. That downloadable
package is the Section 4.6(a) database offer. No additional term from Flight
Alert restricts recipients' ODbL rights.

The application source code and third-party live services are separate from
this database offer. The reference package does not redistribute Esri imagery,
live aircraft traffic, or FAA aviation data.
