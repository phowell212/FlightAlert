# Map Tools

`build_reference_labels.py` rebuilds the bundled local reference label asset from Natural Earth public-domain GeoJSON.

```powershell
python .\tools\map\build_reference_labels.py
```

The script writes:

- `app/src/main/assets/reference/reference_labels_v1.json`
- `tmp/natural-earth/` download cache

Review the generated asset diff before committing it.
