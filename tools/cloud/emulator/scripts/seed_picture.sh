# Seed a picture project and show what the Studio toolbar offers for it (the reusable helper is in lib_app.sh).
. "$HERE_LIB/lib_app.sh"
seed_picture_project || echo "seed_picture_project stopped early"
dump seeded
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
