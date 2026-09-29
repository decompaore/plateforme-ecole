package bf.edutech.plateforme.mobilemoney;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;

/** Agrégateurs disponibles, par code. */
@Component
class Agregateurs {

    private final Map<String, Agregateur> parCode;
    private final MobileMoneyProperties proprietes;

    Agregateurs(List<Agregateur> agregateurs, MobileMoneyProperties proprietes) {
        this.parCode = agregateurs.stream().collect(Collectors.toMap(Agregateur::code, Function.identity()));
        this.proprietes = proprietes;
    }

    Agregateur exiger(String code) {
        Agregateur a = parCode.get(code);
        if (a == null || (AgregateurSimule.CODE.equals(code) && !proprietes.simulateurAutorise())) {
            throw new RegleMetierException("AGREGATEUR_INCONNU", "Agrégateur « " + code
                    + " » non disponible. Disponibles : " + codes());
        }
        return a;
    }

    TreeSet<String> codes() {
        TreeSet<String> codes = new TreeSet<>(parCode.keySet());
        if (!proprietes.simulateurAutorise()) {
            codes.remove(AgregateurSimule.CODE);
        }
        return codes;
    }
}
