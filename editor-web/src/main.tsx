import { createRoot } from "react-dom/client"
import "./styles.css"
import "./legacy-theme.css"
import { App } from "./App"

createRoot(document.getElementById("root")!).render(<App />)
