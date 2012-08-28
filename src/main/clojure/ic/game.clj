(ns ic.game
  (:require [ic.map])
  (:require [ic.units])
  (:use [ic.command])
  (:require [ic.player])
  (:require [mc.util])
  (:import [mikera.persistent.SparseMap])
  (:import [mikera.persistent.IntMap])
  (:import [mikera.util.Rand])
  (:use [mc.util])
  (:use [ic.protocols])
  (:use [clojure.test]))

(declare new-unit-id)
(declare handle-command-event)
(declare handle-time-event)
(declare handle-turn-event)
(declare handle-god-command)
(declare ai-evaluation)

(def TURN_LENGTH_MILLIS (long 10000))
(def AI_THINK_MILLIS (long 800))
(def MAX_AI_TURN_MILLIS (long 2400))


;^mikera.persistent.SparseMap terrain 
;^mikera.persistent.SparseMap units
;^mikera.persistent.SparseMap effects
;^mikera.persistent.SparseMap visibility
;^mikera.persistent.IntMap players
;^mikera.persistent.IntMap unit-locations

(defrecord Game 
  []
  PGame
    (get-terrain [g x y]
      (mget (:terrain g) x y))
    (set-terrain [g x y t]
      (assoc g :terrain (mset (:terrain g) x y t)))
    (get-updates [g event]
      (let [type (:command-type event)]
        (case type
          "Command" (handle-command-event g event)
          "God-Command" (handle-god-command g event)
          "Tick tock" (throw (Error. "Use (get-ai-commands..) instead"))
          "Next turn" 
            (let [updates (handle-turn-event g event)]
              ;(println updates)
              updates)
          (throw (Error. (str "Event type not recognised: " event))))))
    (update [g update]
      (let [type ( :update-type update)] 
        ;(println (str "Game update: " update))
        (case type
          "Unit property"
            (assoc g
              :units
              (let [units (:units g)
                    uid (:uid update)
                    ^ic.map.Point pos (.get ^mikera.persistent.IntMap (:unit-locations g) (int uid))
                    x (.x pos)
                    y (.y pos)
                    unit (get-unit g x y)
                    updated-unit (assoc unit (:property update) (:value update))]
                (mset units x y updated-unit)) )
          "Unit properties"
            (assoc g
              :units
              (let [units (:units g)
                    uid (:uid update)
                    ^ic.map.Point pos (.get ^mikera.persistent.IntMap (:unit-locations g) (int uid))
                    x (.x pos)
                    y (.y pos)
                    unit (get-unit g x y)
                    updated-unit (merge unit (:properties update))]
                (mset units x y updated-unit)) )
          "Player properties"
            (let [players ^mikera.persistent.IntMap (:players g)
                  pid (int (:player-id update))
                  player (.get players pid)
                  props (:properties update)]
              (assoc g :players 
                (.include players pid (merge player props))))          
          "Remove unit"
            (let [units (:units g)
                  uid (:uid update)
                  ^ic.map.Point pos (.get ^mikera.persistent.IntMap (:unit-locations g) (int uid))
                  tx (.x pos)
                  ty (.y pos)]
                (-> g
                  (remove-unit tx ty))) 
          "Add unit"
            (let [unit (:unit update)
                  tx (:tx update)
                  ty (:ty update)]
                (-> g
                  (add-unit tx ty unit))) 
          "Set terrain"
            (let [t ^ic.map.Terrain (:terrain update)
                  tx (int (:tx update))
                  ty (int (:ty update))]
                (set-terrain g tx ty t)) 
          "Next turn"
            (let [next-turn-number (inc (:turn-number g))]
              (-> g
                (assoc :turn-number next-turn-number)
                ((fn [game] 
                   (assoc game :players
                     (reduce
                       (fn [^mikera.persistent.IntMap np [player-id player]]
                         (.include np player-id (assoc player :ai-evaluation (ai-evaluation game player))))
                       mikera.persistent.IntMap/EMPTY
                       (:players game))))))) 
          "Move unit"
            (let [uid (:uid update)
                  ^ic.map.Point pos (.get ^mikera.persistent.IntMap (:unit-locations g) (int uid))
                  sx (:sx update)
                  sy (:sy update)
                  tx (:tx update)
                  ty (:ty update)
                  unit (get-unit g sx sy)]
                (-> g
                  (remove-unit sx sy)
                  (add-unit tx ty unit))) 
          "Failed command"
            (do 
              (println (str "Failed command: " update))
              g)
          (throw (Error. (str "Update type not recognised: " update))))))
    (get-map [g]
      (:terrain g))
    (get-unit [g x y]
      (mget (:units g) x y))
    (get-unit [g uid]
      (let [^ic.map.Point p (.get ^mikera.persistent.IntMap (:unit-locations g) uid)]
        (if (nil? p)
          nil
          (mget (:units g) (.x p) (.y p)))))
    (location-of-unit [g u]
      (.get ^mikera.persistent.IntMap (:unit-locations g) (:id u)))
    (add-unit [g x y u]
      (let [current-id (:id u)
            ^Integer uid (or current-id (new-unit-id g))
            u-with-id (if current-id u (assoc u :id uid))]
	      (-> g
	        (assoc :units (mset (:units g) x y u-with-id))
	        (assoc :unit-locations 
            (let [^mikera.persistent.IntMap ul (:unit-locations g)] 
              (.include ul uid (ic.map/point x y)))))))
    (add-player [g p]
	    (let [players ^mikera.persistent.IntMap (:players g)
            pid (mikera.util.Rand/r 1000000)] 
        (if (nil? (.get players pid))    
          (assoc g :players (.include players pid (merge p {:id pid})))
          (recur p))))
    (update-player [g p]
      (let [players ^mikera.persistent.IntMap (:players g)
            pid (int (:id p))]
        (assoc g :players (.include players pid p))))    
    (get-player [g player-id]
      (let [players ^mikera.persistent.IntMap (:players g)]
        (.get players player-id)))
    (remove-unit [g x y]
      (let [^PUnit u (mget (:units g) x y)
            ^Integer uid (:id u)]
	      (-> g
	        (assoc :units (mset (:units g) x y nil))
          (assoc :unit-locations 
            (let [^mikera.persistent.IntMap ul (:unit-locations g)] 
              (.delete ul uid))))))
    (get-unit-map [g]
      (:units g)))

(defn new-unit-id [^Game g]
  "Creates a new, unused unit ID for the given game"
  (let [^mikera.persistent.IntMap ul (:unit-locations g)
        id (mikera.util.Rand/nextInt)
        cu (.get ul id)]
    (if (nil? cu)
      id
      (recur g))))

(defn active-sides [game]
  "Returns a sorted list of sides with active units"
  (sort (reduce
    (fn [ss [uid ^ic.map.Point pos]]
      (let [unit (get-unit game (.x pos) (.y pos))
            player-side (:side unit)]
        (if (not (list-contains? ss player-side))
          (conj ss player-side)
          ss)))
    '()
    (:unit-locations game))))

(defn get-player-for-side [g side]
  (find-first
    (fn [player]
      (= (:side player) side))
    (.values ^mikera.persistent.IntMap (:players g))))

(defn side-has-human? [g side]
  (some (fn [[i p]] (and (= side (:side p)) (:is-human p))) (:players g)))

(defn handle-god-command [g event]
  "Handle god commands - checks validity of command and translates into game updates."
  (let [update (:update event)]
    (case (:update-type update)
      "Add unit" 
        (let [tx (:tx update)
              ty (:ty update)
              u (get-unit g tx ty)]
          (concat
            (if (nil? u)
              '()
              (list (msg-remove-unit u tx ty)))
            (list update)))
      "Remove unit"
        (let [tx (:tx update)
              ty (:ty update)
              u (get-unit g tx ty)]
          (if (nil? u)
            '()
            (list (msg-remove-unit u tx ty))))
      "Set terrain"
        (let [tx (:tx update)
              ty (:ty update)
              terrain (:terrain update)]
           (list (msg-set-terrain tx ty terrain)))
      "Player properties"
        (list update)
      "Unit property"
        (list update)
      "Unit properties"
        (list update)
      (throw (Error. (str "God command not handled: " event))))))

(defn handle-command-event [^Game g command]
  "Handle a command event to a single unit"
  (let [{ cmd :command-type  uid :uid abname :ability  tx :tx ty :ty player-id :player-id} command
        sp ^ic.map.Point (.get ^mikera.persistent.IntMap (:unit-locations g) uid)]
    ;(println event)
    (cond 
      (nil? sp)
        (list (msg-command-fail (str "Unit not found for command " command)))
      :default
	      (let [x (.x sp)
	            y (.y sp)
	            u (get-unit g x y)] 
	        (cond 
            (not= player-id (:player-id u))
              (list (msg-command-fail (str "Illegal player ID " player-id " for unit " u)))
            :default
		          (let [ability (ic.units/find-ability u abname)] 
		            (apply-to-game ability g u x y tx ty command)))))))

; ===================================================================================
;
; AI map evaluation - build a map of values for the current game state

(defn make-influence-map [^doubles arr ^Integer ox ^Integer oy ^Integer w ^Integer h]
  "Converts an array of doubles into a SparseMap"
  (let [m (atom (ic.map/new-map))]
;    (println (str "influence-map: " w "*" h " at " ox "," oy))
    (dotimes [ix w] (dotimes [iy h]
      (let [v ^Double (aget arr (unchecked-add ix (unchecked-multiply iy w)))]
		    (reset! m (.update 
		                 ^mikera.persistent.SparseMap @m 
		                 (+ ox ix) 
		                 (+ oy iy)
                     v)))))
    @m))

(defn threat-radius [unit]
  (:threat-radius unit))

(defn threat-level [unit]
  (:threat-level unit))

(defn ^Double objective-value [unit]
  (:value unit))


(defn ^Double calc-threat-level [unit sx sy tx ty]
  (let [dist (mikera.engine.Hex/distance sx sy tx ty)
        radius (threat-radius unit)]
    (if (<= dist radius)
      (* (threat-level unit) (- 1 (/ dist radius 2)))
      0)))

(defn ^Double calc-objective-value [unit sx sy tx ty]
  (let [dist (mikera.engine.Hex/distance sx sy tx ty)
        factor (+ 1.0 (* 0.2 dist))]
    (/ (objective-value unit) (* factor factor factor))))

(defn ai-evaluation [game player]
  (let [terrain ^mikera.persistent.SparseMap (get-map game)
        units (:units game)
        bounds ^mikera.math.Bounds4i (.getNonNullBounds terrain)
        ox (.xmin bounds)
        oy (.ymin bounds)
        w (unchecked-subtract (inc (.xmax bounds)) ox)
        h (unchecked-subtract (inc (.ymax bounds)) oy)
        asize (unchecked-multiply w h)
        enemy-threat-level ^doubles (make-array Double/TYPE asize)
        friendly-threat-level ^doubles (make-array Double/TYPE asize)
        objective-value ^doubles (make-array Double/TYPE asize)
        side (:side player)]
	  (do
	    (dotimes [ix w] (dotimes [iy h]
        (let [x (unchecked-add ix ox)
              y (unchecked-add iy oy)
              ind (unchecked-add ix (unchecked-multiply iy w))]
          (mvisit units
            (fn [ux uy unit]
              (if (= side (:side unit))
                (aset friendly-threat-level ind (+ (double (aget friendly-threat-level ind)) (double (calc-threat-level unit ux uy x y))))
                (do
                  (aset enemy-threat-level ind (+ (double (aget enemy-threat-level ind)) (double (calc-threat-level unit ux uy x y))))
                  (aset objective-value ind (+ (double (aget objective-value ind)) (double (calc-objective-value unit ux uy x y)))))))))))
     
      {:enemy-threat-level (make-influence-map enemy-threat-level ox oy w h)
		   :friendly-threat-level (make-influence-map friendly-threat-level ox oy w h)
		   :objective-value (make-influence-map objective-value ox oy w h)}
      )))


; AI logic

(defn ai-ready-to-move [millis base]
  (< (long (/ base AI_THINK_MILLIS)) (long (/ (+ base millis) AI_THINK_MILLIS))))



(defn ai-action [game unit x y]
  (let [command (ic.units/ai-best-command game unit x y)]
    ;(println (str "Command: " command))
    (if (nil? command)
      '()
      (list (assoc command :player-id (:player-id unit))))))

(defn is-controlled? [unit player]
  (= (:id player) (:player-id unit)))

(defn is-ai-controlled-unit? [game unit]
  (let [player (get-player game (:player-id unit))] 
    (:ai-controlled player)))

; time update handling

(defn get-ai-command [^Game g player u x y]
  "Creates ai action command list for a single unit"
  (if 
    (or (> (:aps u) 0) (not (empty? (:contents u))))
    (ai-action g u x y)
    '()))

(defn get-ai-commands 
  ([^Game g ]
    (get-ai-commands g (fn [u] true)))

  ([^Game g unit-pred]
    "Gets a list of all ai-commands for the given game"
    (let [^mikera.persistent.IntMap unit-locs (:unit-locations g)
          ^mikera.persistent.SparseMap units (:units g)]
      ;(println event)
      (mapcat
        (fn [^ic.map.Point pos]
          (let [x (.x pos)
                y (.y pos)
                u (mget units x y)
                player (.get ^mikera.persistent.IntMap (:players g) (int (:player-id u)))] 
            (if 
              (and 
                (:ai-controlled player)
                (unit-pred u))
              (get-ai-command g player u x y))))
        (seq (.values unit-locs))))))

; next turn handling

(defn handle-unit-turn [^Game g u x y millis base]
  "Handles the end of turn event for a unit"
  (let [apsmax (:apsmax u)
        ap-recharge-period (if (> apsmax 0) (/ (* 100 TURN_LENGTH_MILLIS) apsmax (:recharge u)) 1000000)
        ap-bonus (mikera.util.Maths/quantize millis ap-recharge-period base)
        aps (:aps u)
        contents (:contents u)
        new-aps (int (min apsmax (+ ap-bonus (max 0 aps))))]
    (list-not-nil
	    (if 
	      (not (= aps new-aps))
	      (msg-update-unit u :aps new-aps)
	      nil)
      (if
        (not (empty? contents))
        (msg-update-unit u :contents 
          (map-vector (fn [unit] (assoc unit :aps (:apsmax unit))) contents))
        nil))))


(defn handle-turn-event [^Game g event]
  "Handles the End of Turn event"
  (let [{ cmd :command-type } event
        ^mikera.persistent.IntMap unit-locs (:unit-locations g)
        resource-tally (atom {})]
    ;(println event)
    ; TODO: fix base time update
    (concat
	    (doall (mapcat
	      (fn [^ic.map.Point pos]
	        (let [x (.x pos)
	              y (.y pos)
	              u (get-unit g x y) 
               resource-impact (:resource-per-turn u)
               player-id (:player-id u)]
            (if resource-impact 
              (do
;	              (println resource-impact)
                (swap! resource-tally 
	                (fn [old-tally]
		                (assoc old-tally player-id 
		                  (+ (or (old-tally player-id) 0) resource-impact)))))) 
	          (handle-unit-turn g u x y TURN_LENGTH_MILLIS 0)))
        (seq (.values unit-locs))))
      (doall (map 
        (fn [[player-id impact]]
          ;(println (str player-id " resources + : " impact))
          (let [player (get-player g player-id)
                new-total (max 0 (+ impact (:resources player)))]
;            (println "Add resources for " (:name player) ": " impact " -> " new-total " total")
            (msg-update-player player 
              {:resources new-total})))
        @resource-tally))
      (list (msg-next-turn)))))


(defn new-units-map [] 
  (mikera.persistent.SparseMap.))

(defn validate-game [^Game g]
  (let [^mikera.persistent.IntMap unit-locs (:unit-locations g)
        ^mikera.persistent.SparseMap units (:units g)
        players ^mikera.persistent.IntMap (:players g)]
	  (doseq [[uid ^ic.map.Point loc] unit-locs]
	    (let [u (get-unit g (.x loc) (.y loc))
           player-id (:player-id u)]
		    (assert (instance? ic.map.Point loc))
		    (assert (= uid (:id u)))
	      (assert (not (nil? player-id)))
        (assert (not (nil? (.get players player-id))))
		    (assert (ic.units/validate-unit u))))
	  (doseq [[pid player] (:players g)]
	    (let []
	      (assert (= pid (:id player))))))
  true)

(defn new-game [] 
  (Game.
    nil 
    {:terrain (ic.map/new-map)
     :units (new-units-map)
     :effects (mikera.persistent.SparseMap.)
     :visibility (mikera.persistent.SparseMap.)
     :players (mikera.persistent.IntMap/EMPTY)
     :unit-locations (mikera.persistent.IntMap/EMPTY)
     :turn-number 1}))


(deftest t2
  (let [ng (-> (new-game) 
             (add-player (ic.player/player {:name "Test player" :side 0 :is-human true}))
             (set-terrain 2 2 (ic.map/terrain "Grassland")))
        player (get-player-for-side ng 0)
        player-id (:id player)
        u (ic.units/unit "Steam Tank" {:player-id player-id})
        u2 (ic.units/unit "Rifles" {:player-id player-id})
        g (-> ng
            (add-unit 2 2 u)
            (add-unit 3 3 u2))]
    (is (side-has-human? g 0))
    (is (not (side-has-human? g 1)))
    (is (= [0] (active-sides g)))
    (is (not (nil? (get-unit g 2 2))))
    (is (nil? (get-unit (remove-unit g 2 2) 2 2)))
    (validate-game g)
    (ai-evaluation ng player)))

(deftest t-terrain
  (let [g (new-game)
        t (ic.map/terrain "Grassland")]
    (is (nil? (get-terrain g -5 -5)))
    (is (= t (get-terrain (set-terrain g -5 -5 t) -5 -5)))))


