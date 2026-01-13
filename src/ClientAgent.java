import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.util.Logger;

import java.util.Random;

public class ClientAgent extends Agent {
    private final Logger log = Logger.getMyLogger(getClass().getName());
    private final Random rnd = new Random();

    private String dispatcherName = "dispatcher";
    private int startDelayMs = 1500;

    @Override
    protected void setup() {
        Object[] a = getArguments();
        if (a != null && a.length >= 1) dispatcherName = a[0].toString();
        if (a != null && a.length >= 2) startDelayMs = Integer.parseInt(a[1].toString());

        addBehaviour(new WakerBehaviour(this, startDelayMs) {
            @Override
            protected void onWake() {
                sendTicket();
            }
        });

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = receive(
                        MessageTemplate.MatchProtocol(ServiceOntology.PROTOCOL_RESULT)
                );
                if (msg == null) {
                    block();
                    return;
                }

                log.info(getLocalName() + ": получен ответ\n" + msg.getContent());
            }
        });

        log.info(getLocalName() + ": клиент готов");
    }

    private void sendTicket() {
        String[] issues = {
                "Не включается устройство",
                "Быстро разряжается батарея",
                "Трещина на экране",
                "Проблемы с Wi-Fi",
                "Медленная работа системы"
        };

        String issue = issues[rnd.nextInt(issues.length)];

        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.addReceiver(new AID(dispatcherName, AID.ISLOCALNAME));
        req.setLanguage(ServiceOntology.LANG);
        req.setProtocol(ServiceOntology.PROTOCOL_SUBMIT);
        req.setContent(issue);

        send(req);
        log.info(getLocalName() + ": отправил заявку — " + issue);
    }
}
