import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.util.Logger;

import java.util.*;

public class DispatcherAgent extends Agent {
    private final Logger log = Logger.getMyLogger(getClass().getName());

    private final List<AID> technicians = new ArrayList<>();
    private final Map<String, Boolean> busyByTech = new HashMap<>();

    private final Deque<Ticket> queue = new ArrayDeque<>();
    private final Map<String, Ticket> inFlightByConv = new HashMap<>();

    private int rr = 0;
    private long lastQueueLogAt = 0L;

    @Override
    protected void setup() {
        registerAsDispatcher();

        addBehaviour(new TickerBehaviour(this, 700) {
            @Override
            protected void onTick() {
                refreshTechnicians();
                tryDispatch();
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }

                if (msg.getPerformative() == ACLMessage.REQUEST
                        && ServiceOntology.LANG.equals(msg.getLanguage())
                        && ServiceOntology.PROTOCOL_SUBMIT.equals(msg.getProtocol())) {
                    onClientRequest(msg);
                    return;
                }

                if (ServiceOntology.PROTOCOL_ASSIGN.equals(msg.getProtocol())) {
                    onTechnicianReply(msg);
                }
            }
        });

        log.info(getLocalName() + ": диспетчер запущен");
        refreshTechnicians();
        logQueue("старт системы");
    }

    private void onClientRequest(ACLMessage msg) {
        String issue = msg.getContent() == null ? "" : msg.getContent().trim();
        if (issue.isEmpty()) {
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.NOT_UNDERSTOOD);
            reply.setLanguage(ServiceOntology.LANG);
            reply.setProtocol(ServiceOntology.PROTOCOL_RESULT);
            reply.setContent("Пустое описание заявки");
            send(reply);
            return;
        }

        Ticket t = new Ticket(UUID.randomUUID().toString(), msg.getSender(), issue);
        queue.addLast(t);
        logQueue("принята заявка " + t.ticketId);

        ACLMessage ack = msg.createReply();
        ack.setPerformative(ACLMessage.AGREE);
        ack.setLanguage(ServiceOntology.LANG);
        ack.setProtocol(ServiceOntology.PROTOCOL_RESULT);
        ack.setContent("Заявка принята, id=" + t.ticketId);
        send(ack);

        log.info(getLocalName() + ": принята заявка " + t.ticketId +
                " от " + msg.getSender().getLocalName() +
                " (проблема: " + issue + ")");

        tryDispatch();
    }

    private void onTechnicianReply(ACLMessage msg) {
        String conv = msg.getConversationId();
        if (conv == null) return;

        Ticket t = inFlightByConv.get(conv);
        if (t == null) return;

        String techName = msg.getSender().getName();

        if (msg.getPerformative() == ACLMessage.REFUSE) {
            busyByTech.put(techName, true);

            log.info(getLocalName() + ": мастер " + msg.getSender().getLocalName() +
                    " отказался (занят), заявка " + t.ticketId + " будет переназначена");

            t.tried.add(techName);
            inFlightByConv.remove(conv);
            queue.addFirst(t);

            logQueue("переназначение " + t.ticketId + " (отказ " + msg.getSender().getLocalName() + ")");
            tryDispatch();
            return;
        }

        if (msg.getPerformative() == ACLMessage.INFORM) {
            busyByTech.put(techName, false);

            inFlightByConv.remove(conv);
            logQueue("выполнено " + t.ticketId + " (" + msg.getSender().getLocalName() + ")");

            ACLMessage toClient = new ACLMessage(ACLMessage.CONFIRM);
            toClient.addReceiver(t.client);
            toClient.setLanguage(ServiceOntology.LANG);
            toClient.setProtocol(ServiceOntology.PROTOCOL_RESULT);
            toClient.setConversationId("result-" + t.ticketId);
            toClient.setContent(
                    "Заявка выполнена\n" +
                            "ID: " + t.ticketId + "\n" +
                            "Мастер: " + msg.getSender().getLocalName() + "\n" +
                            "Результат: " + msg.getContent()
            );
            send(toClient);

            log.info(getLocalName() + ": заявка " + t.ticketId +
                    " успешно выполнена мастером " + msg.getSender().getLocalName());

            tryDispatch();
            return;
        }

        if (msg.getPerformative() == ACLMessage.FAILURE) {
            busyByTech.put(techName, false);

            log.info(getLocalName() + ": мастер " + msg.getSender().getLocalName() +
                    " сообщил об ошибке при обработке заявки " + t.ticketId);

            t.tried.add(techName);
            inFlightByConv.remove(conv);
            queue.addFirst(t);

            logQueue("повтор " + t.ticketId + " (ошибка " + msg.getSender().getLocalName() + ")");
            tryDispatch();
        }
    }

    private void tryDispatch() {
        if (queue.isEmpty()) return;

        while (!queue.isEmpty()) {
            Ticket t = queue.peekFirst();

            if (technicians.isEmpty()) {
                logQueue("нет мастеров в DF, заявка " + t.ticketId + " ждёт");
                log.info(getLocalName() + ": мастера ещё не найдены в DF, заявка " + t.ticketId + " ожидает");
                doWait(250);
                break;
            }

            AID tech = pickFreeTechnician(t);

            if (tech == null) {
                queue.removeFirst();
                t.tried.clear();
                queue.addLast(t);

                logQueue("все мастера заняты, заявка " + t.ticketId + " ждёт в очереди");
                log.info(getLocalName() + ": все мастера заняты, заявка " + t.ticketId + " перенесена в конец очереди");

                doWait(250);
                break;
            }

            queue.removeFirst();

            String conv = "assign-" + t.ticketId + "-" + System.nanoTime();
            inFlightByConv.put(conv, t);

            busyByTech.put(tech.getName(), true);

            ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
            req.addReceiver(tech);
            req.setLanguage(ServiceOntology.LANG);
            req.setProtocol(ServiceOntology.PROTOCOL_ASSIGN);
            req.setConversationId(conv);
            req.setContent(
                    "ID заявки: " + t.ticketId +
                            "\nПроблема: " + t.issue +
                            "\nКлиент: " + t.client.getLocalName()
            );

            logQueue("назначение " + t.ticketId + " -> " + tech.getLocalName());
            send(req);

            log.info(getLocalName() + ": заявка " + t.ticketId +
                    " назначена мастеру " + tech.getLocalName());
        }
    }

    private AID pickFreeTechnician(Ticket t) {
        int n = technicians.size();
        for (int k = 0; k < n; k++) {
            int idx = (rr + k) % n;
            AID cand = technicians.get(idx);

            String name = cand.getName();
            boolean busy = busyByTech.getOrDefault(name, false);

            if (!busy && !t.tried.contains(name)) {
                rr = (idx + 1) % n;
                return cand;
            }
        }
        return null;
    }

    private void registerAsDispatcher() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(ServiceOntology.SERVICE_DISPATCHER);
            sd.setName("Диспетчер сервисного центра");
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new RuntimeException(e);
        }
    }

    private void refreshTechnicians() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(ServiceOntology.SERVICE_TECHNICIAN);
            template.addServices(sd);

            DFAgentDescription[] found = DFService.search(this, template);

            Set<String> newSet = new HashSet<>();
            technicians.clear();
            for (DFAgentDescription d : found) {
                AID aid = d.getName();
                technicians.add(aid);
                newSet.add(aid.getName());
            }

            technicians.sort(Comparator.comparing(AID::getName));
            if (rr >= technicians.size()) rr = 0;

            busyByTech.keySet().removeIf(k -> !newSet.contains(k));
            for (AID t : technicians) busyByTech.putIfAbsent(t.getName(), false);

        } catch (FIPAException e) {
            technicians.clear();
            busyByTech.clear();
            rr = 0;
        }
    }

    private void logQueue(String reason) {
        int inQueue = queue.size();
        int inWork = inFlightByConv.size();

        long now = System.currentTimeMillis();
        if (now - lastQueueLogAt < 250 && (reason == null || reason.isBlank())) return;
        lastQueueLogAt = now;

        String r = (reason == null || reason.isBlank()) ? "" : (" | " + reason);
        log.info(getLocalName() + ": состояние очереди -> в очереди: " + inQueue + ", в работе: " + inWork + r);
    }

    private static final class Ticket {
        final String ticketId;
        final AID client;
        final String issue;
        final Set<String> tried = new HashSet<>();

        Ticket(String ticketId, AID client, String issue) {
            this.ticketId = ticketId;
            this.client = client;
            this.issue = issue;
        }
    }
}
