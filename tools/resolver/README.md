The runtime catalog is generated from the three frozen JSON authoring inputs in
`src/test/resources/com/tickreplaylogger`. Production code reads only the generated
catalog; these tools never access the game, network, or Git history.

From the repository root, run `python tools/resolver/build_runtime_mechanics.py`.
Use `--check` to verify an unchanged deterministic output. Python 3.9 or newer and
its standard library are sufficient. `--input-dir` accepts an explicit copy of the
same three input files. Source hashes and their original commit remain provenance,
not build dependencies.

The lowerers preserve every admitted selector, qualification, component bound,
reference annotation and provenance field. Generic callback/native/temporal
integrity checks remain Java application logic; the resource contains mechanics
facts, their source constraints and annotation effects.
