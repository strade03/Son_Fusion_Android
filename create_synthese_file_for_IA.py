# -*- coding: utf-8 -*-
import os
import glob

dossier_racine = '.'
dossier_sortie = 'tmp_IA'
# 'all_html.txt': {'dossier': 'css', 'extension': '.css'} => pas de récurence, il fusionne tout les css du dossier css
# 'all_html.txt': {'dossier': 'css/*', 'extension': '.css'} => récurence, il fusionne tout les css du dossier css et sous dossier
# 'myapp.txt': {'dossier': 'app/src/*','extension': ['.java', '.xml']} fusion dans le même fichier en récurence java et xml trouvé


taches_de_compilation = {
    # Application Web
    'all_html.txt': {'dossier': '.', 'extension': '.html'},
    'all_html_templates.txt': {'dossier': 'templates', 'extension': '.html'},
    'all_css.txt':  {'dossier': 'css', 'extension': '.css'},
    'all_js.txt':   {'dossier': 'js', 'extension': '.js'},
    'all_texts_js.txt': {'dossier': os.path.join('js', 'texts'), 'extension': '.js'},
    'all_php.txt':  {'dossier': 'api', 'extension': '.php'},
    
    # Application C++
    'all_cpp.txt':  {'dossier': '.', 'extension': '.cpp'},
    'all_h.txt':  {'dossier': '.', 'extension': '.h'},
    'all_ui.txt':  {'dossier': '.', 'extension': '.ui'},
    'all_qrc.txt':  {'dossier': '.', 'extension': '.qrc'},
    'all_pro.txt':  {'dossier': '.', 'extension': '.pro'},
    'all_plist.txt':  {'dossier': '.', 'extension': '.plist'},
    # Application Android
    'all_kt_java_xml.txt':  {'dossier': 'app/src/*', 'extension': ['.java','.xml','.kt']},
    'all_gradle.txt':  {'dossier': './*', 'extension': ['.gradle']},
    
}

if not os.path.exists(dossier_sortie):
    os.makedirs(dossier_sortie)
    print(f"Dossier '{dossier_sortie}' créé.")

def compiler_fichiers_par_dossier(dossier_base, dossier_cible, extension_cible, fichier_de_sortie):
    # Normaliser extension_cible en liste
    if isinstance(extension_cible, str):
        extensions = [extension_cible]
    else:
        extensions = extension_cible  # déjà une liste

    if '*' in dossier_cible:
        # Ergonomie : 'dossier/*' → 'dossier/**/*'
        if dossier_cible.endswith('/*'):
            dossier_cible = dossier_cible[:-2] + '/**/*'
        
        # On va chercher tous les fichiers correspondant à n'importe quelle extension
        fichiers_trouves = set()  # pour éviter les doublons si jamais
        for ext in extensions:
            motif = os.path.join(dossier_base, dossier_cible) + ext
            print(f"Recherche avec motif : {motif}")
            trouves = glob.glob(motif, recursive=True)
            fichiers_trouves.update(f for f in trouves if os.path.isfile(f))
        fichiers_trouves = sorted(fichiers_trouves)  # tri pour reproductibilité
    else:
        chemin_dossier_a_scanner = os.path.join(dossier_base, dossier_cible)
        if not os.path.isdir(chemin_dossier_a_scanner):
            print(f"-> AVERTISSEMENT : Le dossier '{chemin_dossier_a_scanner}' est introuvable et sera ignoré.")
            return

        print(f"Analyse du dossier '{chemin_dossier_a_scanner}' pour les extensions {extensions}...")
        fichiers_trouves = []
        for nom_fichier in os.listdir(chemin_dossier_a_scanner):
            if any(nom_fichier.endswith(ext) for ext in extensions):
                chemin_complet = os.path.join(chemin_dossier_a_scanner, nom_fichier)
                if os.path.isfile(chemin_complet):
                    fichiers_trouves.append(chemin_complet)

    if not fichiers_trouves:
        print(f"  -> Aucun fichier trouvé avec les extensions {extensions}.")
        return

    chemin_sortie = os.path.join(dossier_sortie, fichier_de_sortie)
    with open(chemin_sortie, 'w', encoding='utf-8') as f_sortie:
        for chemin_fichier in fichiers_trouves:
            print(f"  -> Compilation du fichier : {chemin_fichier}")
            f_sortie.write("******************\n")
            f_sortie.write(f"Fichier : {chemin_fichier}\n")
            f_sortie.write("******************\n\n")

            try:
                with open(chemin_fichier, 'r', encoding='utf-8', errors='ignore') as f_entree:
                    contenu = f_entree.read()
                    f_sortie.write(contenu)
            except Exception as e:
                f_sortie.write(f"Erreur lors de la lecture du fichier : {e}")
            f_sortie.write("\n\n")

    print(f"==> Fichier '{chemin_sortie}' créé avec succès.\n")


if __name__ == "__main__":
    print("Démarrage du script de compilation par dossier...\n")

    for sortie, config in taches_de_compilation.items():
        compiler_fichiers_par_dossier(
            dossier_base=dossier_racine,
            dossier_cible=config['dossier'],
            extension_cible=config['extension'],
            fichier_de_sortie=sortie
        )

    print("Script terminé.")