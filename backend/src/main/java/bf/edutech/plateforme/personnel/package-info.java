/**
 * Dossier administratif du personnel (v0.51) : un dossier par membre du personnel de l'établissement
 * (enseignants, personnel administratif, agents de soutien sans compte) avec l'identité, la situation
 * administrative (matricule, emploi, grade, échelon), les dates de prise de service et l'ancienneté calculée,
 * les diplômes académiques et professionnels, les pièces classées (arrêtés, notes de service, diplômes
 * scannés) ; les actes établis par l'établissement (certificats de prise et de cessation de service,
 * attestations de présence au poste et de travail, autorisations d'absence), numérotés et signés par le chef
 * d'établissement ; les congés et absences, demandés par l'agent et accordés par le chef d'établissement.
 * Chaque agent qui a un compte consulte son dossier et signale une erreur.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Personnel")
package bf.edutech.plateforme.personnel;
