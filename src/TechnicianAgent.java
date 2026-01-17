import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.util.Logger;

import java.util.Random;

public class TechnicianAgent extends Agent {
    private final Logger log = Logger.getMyLogger(getClass().getName());
    private final Random rnd = new Random();
    private volatile boolean busy = false;

    @Override
    protected void setup() {
        registerAsTechnician();

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) { block(); return; }

                if (msg.getPerformative() != ACLMessage.REQUEST) return;
                if (!ServiceOntology.PROTOCOL_ASSIGN.equals(msg.getProtocol())) return;

                if (busy) {
                    ACLMessage refuse = msg.createReply();
                    refuse.setPerformative(ACLMessage.REFUSE);
                    refuse.setLanguage(ServiceOntology.LANG);
                    refuse.setProtocol(ServiceOntology.PROTOCOL_ASSIGN);
                    refuse.setContent("Мастер занят");
                    send(refuse);

                    log.info(getLocalName() + ": REFUSE — занят (конфликт диспетчеров возможен)");
                    return;
                }

                busy = true;

                // подтверждаем, что взяли в работу (не обязательно, но наглядно)
                ACLMessage agree = msg.createReply();
                agree.setPerformative(ACLMessage.AGREE);
                agree.setLanguage(ServiceOntology.LANG);
                agree.setProtocol(ServiceOntology.PROTOCOL_ASSIGN);
                agree.setContent("Принял заявку в работу");
                send(agree);

                int workMs = 900 + rnd.nextInt(2200);
                boolean fail = rnd.nextDouble() < 0.15;

                log.info(getLocalName() + ": принял заявку, работаю " + workMs + " мс");

                addBehaviour(new WakerBehaviour(myAgent, workMs) {
                    @Override
                    protected void onWake() {
                        ACLMessage out = msg.createReply();
                        out.setLanguage(ServiceOntology.LANG);
                        out.setProtocol(ServiceOntology.PROTOCOL_ASSIGN);

                        if (fail) {
                            out.setPerformative(ACLMessage.FAILURE);
                            out.setContent("Ошибка при диагностике устройства");
                            send(out);
                            log.info(getLocalName() + ": FAILURE по заявке");
                        } else {
                            out.setPerformative(ACLMessage.INFORM);
                            out.setContent("Работа завершена за " + workMs + " мс");
                            send(out);
                            log.info(getLocalName() + ": INFORM — заявка выполнена");
                        }

                        busy = false;
                    }
                });
            }
        });

        log.info(getLocalName() + ": мастер готов к работе");
    }

    private void registerAsTechnician() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(ServiceOntology.SERVICE_TECHNICIAN);
            sd.setName("Мастер сервисного центра");
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new RuntimeException(e);
        }
    }
}
