# Dictionnaires embarqués — origine et licences

Les deux lexiques livrés dans `app/src/main/assets/ime/dict/` sont dérivés des dictionnaires
AOSP LatinIME, distribués sous **Apache License 2.0**, soit exactement la licence de FlorisBoard.
Aucune clause virale, aucune obligation supplémentaire : les deux fichiers sont compatibles
avec la distribution d'un APK local.

## Fichiers livrés

| Fichier | Langue | Mots | Taille | Fréquence minimale conservée |
|---|---|---|---|---|
| `lexicon_fr.json` | Français | 87 284 | 1,3 Mo | 60 / 255 |
| `lexicon_en.json` | Anglais (US) | 59 773 | 817 Ko | 60 / 255 |

Format : `{ "mot": fréquence }`, fréquence sur 0..255, triée par fréquence décroissante.

## Chaîne d'origine

```
AOSP LatinIME (packages/inputmethods/LatinIME, Apache-2.0)
        ↓
OpenBoard v1.4.5 — dictionaries/fr_wordlist.combined.gz
        ↓
Helium314/aosp-dictionaries (Codeberg) — wordlists/main_fr.combined, main_en_US.combined
        ↓
Filtrage fréquence >= 60, conversion JSON, tri décroissant (ce dépôt)
```

Sources exactes, telles que publiées par le dépôt amont :

- `main_fr.source` : `https://github.com/openboard-team/openboard/blob/v1.4.5/dictionaries/fr_wordlist.combined.gz`
- `main_en_US.source` : liste AOSP LatinIME correspondante

Le format d'entrée était :

```
dictionary=main:fr,locale=fr,description=Français,date=1414726264,version=54
 word=de,f=221,flags=,originalFreq=221
```

## Licence

Apache License, Version 2.0 — texte complet : <https://www.apache.org/licenses/LICENSE-2.0>

Le fichier `LICENSE` à la racine du dépôt couvre l'ensemble du code et de ces données.
Aucun avis de droits d'auteur tiers n'a été retiré : les dictionnaires AOSP n'en portent pas
dans les fichiers de mots eux-mêmes, et leur licence d'origine est l'Apache-2.0.

## Alternatives écartées

| Source | Licence | Raison du rejet |
|---|---|---|
| hermitdave/FrequencyWords | CC BY-SA 4.0 pour le contenu | Clause partage à l'identique incompatible avec une distribution Apache-2.0 |
| Lexique 3.83 | CC BY-SA | Idem |
| Taknok/French-Wordlist | MIT, mais sans accents | Inutilisable : les accents ont été supprimés, donc `école` devient `ecole` |
| an-array-of-french-words | MIT | Liste de mots sans fréquences : impossible de classer les suggestions |

## Vérifications effectuées

Les lexiques ont été testés sur 40 cas réels avant intégration :

- accents restitués : `ecole → école`, `reunion → réunion`, `ca → ça`, `etre → être`
- élisions : `aujourdhui → aujourd'hui`, `rendezvous → rendez-vous`, `l'homm → l'homme`
- corrections : `exellent → excellent`, `developement → développement`, `recieve → receive`,
  `definately → definitely`, `goverment → government`
- mots corrects laissés tranquilles : `merci`, `salut`, `bonjour`, `travail` ne déclenchent rien
- coût mesuré : 0,01 ms sur le chemin rapide, 35 ms au pire en Python non JIT
  (soit de l'ordre de 1 à 5 ms en Kotlin sur appareil)
